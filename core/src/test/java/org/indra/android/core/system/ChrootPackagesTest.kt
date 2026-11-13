package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rootfs inventory parser behind the Settings card.
 *
 * The fixture here mirrors the real `lib/apk/db/installed` shape: blank-line-separated records of
 * `X:value` lines, with `D:` dependency lines whose values contain spaces. Those spaces are the
 * reason nothing may split on whitespace — an earlier parser idea did, and it turned a dependency
 * list into fields.
 */
class ChrootPackagesTest {
    @Test
    fun `reads name, version and installed bytes per record`() {
        val text =
            """
            C:Q1abc=
            P:busybox
            V:1.36.1-r29
            A:aarch64
            S:123456
            I:212345
            T:Core utilities

            C:Q1def=
            P:iproute2
            V:6.10.0-r0
            S:555555
            I:987654
            """.trimIndent()

        val packages = ChrootPackages.parse(text)

        assertEquals(listOf("busybox", "iproute2"), ChrootPackages.names(packages))
        assertEquals("1.36.1-r29", packages[0].version)
        assertEquals(212345L, packages[0].installedBytes)
        assertEquals(987654L, packages[1].installedBytes)
    }

    @Test
    fun `dependency lines with spaces are not fields`() {
        val text =
            """
            P:iw
            V:6.9-r0
            D:so:libc.musl-aarch64.so.1 libnl3
            I:100
            """.trimIndent()

        val packages = ChrootPackages.parse(text)

        assertEquals(1, packages.size)
        assertEquals("iw", packages[0].name)
        assertEquals(100L, packages[0].installedBytes)
    }

    @Test
    fun `a record without a name is dropped`() {
        val text =
            """
            V:1.0
            I:10

            P:musl
            V:1.2.5
            """.trimIndent()

        assertEquals(listOf("musl"), ChrootPackages.names(ChrootPackages.parse(text)))
    }

    @Test
    fun `a record without a size or version still parses`() {
        val packages = ChrootPackages.parse("P:nano")

        assertEquals(1, packages.size)
        assertEquals("nano", packages[0].name)
        assertEquals("", packages[0].version)
        assertEquals(0L, packages[0].installedBytes)
    }

    @Test
    fun `a repeated key keeps the first value`() {
        val packages = ChrootPackages.parse("P:iw\nP:iproute2\nV:1\nI:5")

        assertEquals("iw", packages.single().name)
    }

    @Test
    fun `carriage returns from a host-edited database are tolerated`() {
        val text = "P:iw\r\nV:6.9\r\nI:42\r\n\r\nP:musl\r\nV:1.2.5\r\n"

        assertEquals(listOf("iw", "musl"), ChrootPackages.names(ChrootPackages.parse(text)))
        assertEquals(42L, ChrootPackages.parse(text)[0].installedBytes)
    }

    @Test
    fun `a malformed line is skipped without losing the record`() {
        // A line whose second character is not ':' cannot be a field. It must not abort the read;
        // one corrupt line in a 30-package database used to be enough to hide the whole rootfs.
        val text = "P:iw\ngarbage\nV:6.9\n"

        val packages = ChrootPackages.parse(text)

        assertEquals("iw", packages.single().name)
        assertEquals("6.9", packages.single().version)
    }

    @Test
    fun `the final record needs no trailing blank line`() {
        assertEquals(listOf("iw", "musl"), ChrootPackages.names(ChrootPackages.parse("P:iw\n\nP:musl")))
    }

    @Test
    fun `an empty database is an empty inventory, not an error`() {
        assertTrue(ChrootPackages.parse("").isEmpty())
        assertTrue(ChrootPackages.parse("\n\n\n").isEmpty())
    }
}
