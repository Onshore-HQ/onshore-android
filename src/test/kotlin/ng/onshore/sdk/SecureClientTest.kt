package ng.onshore.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureClientTest {
    private fun fails(status: Int, text: String): VaultException =
        try {
            SecureClient.interpret(status, text)
            fail("should have thrown")
            throw AssertionError()
        } catch (e: VaultException) {
            e
        }

    @Test fun `errors become their types`() {
        assertEquals(VaultException.InvalidClientToken, fails(401, """{"error":{"code":"invalid_client_token"}}"""))
        assertEquals(VaultException.Denied, fails(403, """{"error":{"code":"policy_denied"}}"""))
        assertEquals(VaultException.RateLimited, fails(429, """{"error":{"code":"rate_limited"}}"""))
        assertEquals(VaultException.Transport, fails(200, "not json"))
        assertTrue(fails(500, """{"error":{"code":"x"}}""") is VaultException.Server)
    }

    @Test fun `a validation failure names fields in either shape`() {
        val a = fails(422, """{"error":{"code":"validation_failed","fields":[{"field":"bvn","code":"invalid"}]}}""") as VaultException.ValidationFailed
        assertEquals(listOf("bvn"), a.fields)
        val b = fails(422, """{"error":{"code":"validation_failed","fields":["nin"]}}""") as VaultException.ValidationFailed
        assertEquals(listOf("nin"), b.fields)
    }

    @Test fun `an error never carries a value`() {
        val e = fails(422, """{"error":{"code":"validation_failed","message":"22333444558","fields":[]}}""")
        assertFalse(e.toString().contains("22333444558"))
    }

    @Test fun `results parse, and a value stays out of toString`() {
        val r = SecureClient.parseResults(JSONArray("""[{"decision":"plaintext","value":"22333444558"},{"error":{"code":"policy_denied"}}]"""))
        assertEquals("22333444558", r[0].value)
        assertFalse(r[0].toString().contains("22333444558"))
        assertNull(r[1].value)
        assertEquals("policy_denied", r[1].errorCode)
    }

    @Test fun `a description parses`() {
        val d = SecureClient.parseDescription(JSONObject("""{"kind":"collect","tokens":0,"fields":[{"name":"bvn","data_type":"bvn"}]}"""))
        assertEquals(ComponentDescription("collect", listOf(FieldDescription("bvn", "bvn")), 0), d)
    }
}
