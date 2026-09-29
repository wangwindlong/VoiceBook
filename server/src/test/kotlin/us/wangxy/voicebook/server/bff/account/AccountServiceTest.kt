package us.wangxy.voicebook.server.bff.account

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import us.wangxy.voicebook.bff.contract.ChangePasswordRequest
import us.wangxy.voicebook.bff.contract.ProvisionResult
import us.wangxy.voicebook.bff.contract.RegisterRequest
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.SecurityConfig
import us.wangxy.voicebook.server.bff.lldap.DirectoryAdmin
import us.wangxy.voicebook.server.bff.lldap.DirectoryPasswords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeDirectory : DirectoryAdmin, DirectoryPasswords {
    val users = mutableMapOf<String, String?>()
    val groups = mutableMapOf<String, List<String>>()
    var failSetPassword = false

    override suspend fun userExists(userId: String) = userId in users
    override suspend fun createUser(userId: String, email: String, displayName: String) { users[userId] = null }
    override suspend fun addUserToGroups(userId: String, groupDisplayNames: List<String>) { groups[userId] = groupDisplayNames }
    override suspend fun deleteUser(userId: String) { users -= userId }
    override suspend fun verify(userId: String, password: String) = password.isNotBlank() && users[userId] == password
    override suspend fun setPassword(userId: String, newPassword: String) {
        if (failSetPassword) throw IllegalStateException("ldap down")
        users[userId] = newPassword
    }
}

class AccountServiceTest {
    private val dir = FakeDirectory()
    private val provisioned = mutableListOf<String>()
    private val service = AccountService(
        directory = dir,
        passwords = dir,
        defaultGroups = listOf("calibre_web"),
        security = SecurityConfig(registrationEnabled = true, registerPerMinute = 5, passwordMinLength = 8),
        provisioners = mapOf(
            "miniflux" to Provisioner { u, _ -> provisioned += "miniflux:$u"; ProvisionResult(true) },
            "calibre" to Provisioner { _, _ -> throw IllegalStateException("cwa down") },
        ),
    )

    @Test
    fun registerCreatesAccountAndReportsProvisioning() = runTest {
        val res = service.register(RegisterRequest("Alice", "alice@example.com", "passw0rd!"))
        assertEquals("alice", res.username)
        assertEquals("passw0rd!", dir.users["alice"])
        assertEquals(listOf("calibre_web"), dir.groups["alice"])
        assertTrue(res.provisioning.getValue("miniflux").ok)
        assertFalse(res.provisioning.getValue("calibre").ok)
        assertTrue(res.provisioning.getValue("artalk").ok)
        assertEquals(listOf("miniflux:alice"), provisioned)
    }

    @Test
    fun duplicateAndInvalidInputAreRejected() = runTest {
        service.register(RegisterRequest("alice", "alice@example.com", "passw0rd!"))
        assertEquals(HttpStatusCode.Conflict, assertFailsWith<BffException> {
            service.register(RegisterRequest("alice", "b@example.com", "passw0rd!"))
        }.status)
        assertEquals("INVALID_USERNAME", assertFailsWith<BffException> {
            service.register(RegisterRequest("admin", "b@example.com", "passw0rd!"))
        }.code)
        assertEquals("WEAK_PASSWORD", assertFailsWith<BffException> {
            service.register(RegisterRequest("bob", "b@example.com", "short1"))
        }.code)
    }

    @Test
    fun failedPasswordSetRollsBackUser() = runTest {
        dir.failSetPassword = true
        assertFailsWith<IllegalStateException> { service.register(RegisterRequest("carol", "c@example.com", "passw0rd!")) }
        assertFalse(dir.users.containsKey("carol"))
    }

    @Test
    fun changePasswordRequiresOldPassword() = runTest {
        service.register(RegisterRequest("dave", "d@example.com", "passw0rd!"))
        assertEquals(HttpStatusCode.Forbidden, assertFailsWith<BffException> {
            service.changePassword("dave", ChangePasswordRequest("wrong", "newpassw0rd"))
        }.status)
        service.changePassword("dave", ChangePasswordRequest("passw0rd!", "newpassw0rd"))
        assertEquals("newpassw0rd", dir.users["dave"])
    }
}
