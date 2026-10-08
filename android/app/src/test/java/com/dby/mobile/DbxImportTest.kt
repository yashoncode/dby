package com.dby.mobile

import com.dby.core.Env
import com.dby.core.TlsMode
import com.dby.mobile.data.WrongPassphrase
import com.dby.mobile.data.dbxNeedsPassphrase
import com.dby.mobile.data.readDbx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DbxImportTest {
    // Made with Python's cryptography package the way dbx's WebCrypto code does it
    // (PBKDF2-SHA256, 100,000 rounds, AES-256-GCM); passphrase "pässword", made-up connections.
    private val encrypted = """{"format": "dbx-encrypted", "version": 1, "salt": "o3m99OJ1HfNomcDXiF5HQA==", "iv": "mh5AVJ7yNP+D6slV", "data": "x8A25FO9jeNJpvFCj7hvlawqhTAG13/6DyTaLb8/kHcI+GUkJ4ZNUdkqW3dW1b7AyFb4BOLBWzpMlHmqBXSEVy6Qb5c0kakJ35XHknFByFGV9XGtVVdHTW7DuemtpG4lIg7DfTmQ5DpFcxA6ZhiFATU/brQZIZO7u1h6cjCq5Q554F2itjBJ0NNxuAcW6fttr7rg2nZqxnPllvMSyf++f6ysPVQvnPES1IOS8x53rgfc5GF7dJzauD8Cj7WB3elJZWj8KBDrWD1jg7dY/5lNdmOoBEWXokePsDqtE/FXAzkobTHSy6xQ+pED5TFY0JEBNlQ+ROmlRI1YBrAFlRVOvYLYl+chPIWay3EOeQiudpjEAU8nBgjb9uSSlG5uCDi85Ji/AEjGb75IHXzgc/DgWSy77ouJwt3p0RLSRh6U0ctMzg1q8FCpPAcPW3C3wWoUlexyFs8gMlLOfCt+KQTrttB/oJyNBmbOA0z0WPSZINX8fVu6VpTIHX/iQQG48asmLonhSS/LKdP4NitAkqH9GHlvRhhVwhq2k/J6Vs1YercVczZrK7pGRXATCFnv/mZOixfrM29lCogz4Hl3sK5UXwZ8whKj9Fc3qKzCofDRfxRD6tTukWHbqdMSDZGA9M5Lbqn2j8XoW7wnBqsObjc7hSSg4FjKkYQ+t5bJd7GGbFIXa68WVH+N4VwqhICTaLAckmgvchLeVlpK5QdBZM5V5jxe9NRCs49YVH5Ihu7m3Lpc93gD5Qcq+OgGc+bvb6Jjt8dAJD5iTrHIwGg3CzdfzrAaD9JjNLBaw0dbWkZnZLTNP9RJNOBiqFmz6bC8QXPUuOlYwRaz5/dl/apLjteyamlVnvw769ELXtdxFxyH+9q5+LPZU6wviwu5+PFx4/VqqudeBr2Tqhy/5Vh7fv+BQ7NByx0RfYNz8o3Jxx/xYKAWKNIOYbBhcWZwGoEaxTqGWaf6tn7jtSdDvkEmA23H0OYq/KHvv50BK26Oo2n+p5DB67tsgaD7mWFqgpip84kWsuSs5ETEHWdozIO5jDKs85PefKB5R5GUiJADFvnUSD5Id2AAvu5/d8c6/CAUzvu1w9iz4IWM/DEDV1Oylxc6jhCJ6Q=="}"""

    @Test
    fun encrypted_export_imports_mysql_and_mariadb_only() {
        assertTrue(dbxNeedsPassphrase(encrypted))
        val file = readDbx(encrypted, "pässword")
        assertEquals(2, file.skipped) // postgres, and the one behind an SSH tunnel
        val (orders, local) = file.connections
        assertEquals("Orders", orders.form.name)
        assertEquals("db.example.com", orders.form.host)
        assertEquals(3307, orders.form.port)
        assertEquals("app", orders.form.user)
        assertEquals("shop", orders.form.database)
        assertEquals(Env.PROD, orders.form.env)
        assertEquals(TlsMode.VERIFY, orders.form.tls)
        assertEquals("s3cret", orders.password)
        assertEquals(Env.LOCAL, local.form.env)
        assertEquals(TlsMode.OFF, local.form.tls)
        assertEquals("", local.form.database)
        assertNull(local.password)
    }

    @Test(expected = WrongPassphrase::class)
    fun wrong_passphrase_is_reported() {
        readDbx(encrypted, "nope")
    }

    @Test
    fun plain_export_needs_no_passphrase() {
        val plain = """{"format":"dbx-config","version":1,"connections":[{"name":"","db_type":"mysql","host":"h","port":3306,"username":"u","password":"","database":"d","ssl":false,"save_password":true}]}"""
        assertFalse(dbxNeedsPassphrase(plain))
        val c = readDbx(plain, null).connections.single()
        assertEquals("h", c.form.name)
        assertNull(c.password) // blank means "not exported": asked for at the first connect
    }

    @Test
    fun dbx_plaintext_export_has_no_format_and_blank_passwords() {
        // What dbx writes for "Export without passphrase": every password scrubbed to "".
        val plain = """{"connections":[{"id":"a","name":"Prod","db_type":"mysql","host":"db.example.com","port":3306,"username":"app","password":"","database":"shop","ssl":true}],"layout":{"groups":[],"order":[]},"tunnelProfiles":[]}"""
        assertFalse(dbxNeedsPassphrase(plain))
        val c = readDbx(plain, null).connections.single()
        assertEquals("Prod", c.form.name)
        assertNull(c.password)
    }

    @Test
    fun legacy_bare_array_export_is_read() {
        val legacy = """[{"name":"Old","db_type":"mariadb","host":"10.0.0.5","port":3307,"username":"u","password":"pw","database":"d"}]"""
        assertFalse(dbxNeedsPassphrase(legacy))
        val c = readDbx(legacy, null).connections.single()
        assertEquals(3307, c.form.port)
        assertEquals("pw", c.password)
    }

    @Test(expected = IllegalArgumentException::class)
    fun other_files_are_refused() {
        readDbx("""{"hello":1}""", null)
    }
}
