package ng.onshore.sdk

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an emulator against a live install. Trusts its test certificate here, in the test only. */
@RunWith(AndroidJUnit4::class)
class LiveInstallTest {
    companion object {
        private val args get() = InstrumentationRegistry.getArguments()
        private val factory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }.socketFactory

        @JvmStatic @BeforeClass fun trustTestHost() {
            HttpsURLConnection.setDefaultHostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }

    private val client get() = SecureClient(args.getString("rvBase")!!, args.getString("rvOrigin")!!, factory)

    @Test fun collectFollowsTheContract() {
        val token = args.getString("rvCollect")!!
        val value = args.getString("rvValue")!!
        val d = client.describe(token)
        assertEquals("collect", d.kind)
        assertEquals("bvn", d.fields[0].name)
        try {
            SecureClient(args.getString("rvBase")!!, "rvapp://ng.other.app", factory).describe(token)
            fail("another app was accepted")
        } catch (_: VaultException.InvalidClientToken) {}
        try {
            client.collect(token, mapOf("bvn" to "nope"))
            fail("a typo was accepted")
        } catch (e: VaultException.ValidationFailed) {
            assertEquals(listOf("bvn"), e.fields)
        }
        val tokens = client.collect(token, mapOf("bvn" to value))
        assertTrue(tokens["bvn"]!!.startsWith("tok_bvn_"))
        assertFalse(tokens["bvn"]!!.contains(value))
        try {
            client.collect(token, mapOf("bvn" to value))
            fail("a client token worked twice")
        } catch (_: VaultException.InvalidClientToken) {}
    }

    @Test fun revealFollowsPolicy() {
        val shown = client.reveal(args.getString("rvReveal")!!)
        assertEquals(args.getString("rvRevealValue"), shown[0].value)
        val denied = client.reveal(args.getString("rvDenied")!!)
        assertNull(denied[0].value)
        assertEquals("policy_denied", denied[0].errorCode)
    }
}
