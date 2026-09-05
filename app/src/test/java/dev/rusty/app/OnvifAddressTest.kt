package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OnvifAddressTest {
    @Test
    fun `bare host tries 8000 then 80`() {
        assertEquals(
            listOf("http://192.168.4.90:8000/onvif/device_service", "http://192.168.4.90:80/onvif/device_service"),
            OnvifAddress.candidates(" 192.168.4.90 "),
        )
    }

    @Test
    fun `explicit port is the only candidate`() {
        assertEquals(listOf("http://cam.local:8899/onvif/device_service"), OnvifAddress.candidates("cam.local:8899"))
    }

    @Test
    fun `a pasted http url keeps its host and port`() {
        assertEquals(listOf("http://192.168.4.90:8000/onvif/device_service"), OnvifAddress.candidates("http://192.168.4.90:8000/onvif/device_service"))
        assertEquals(
            listOf("http://192.168.4.91:8000/onvif/device_service", "http://192.168.4.91:80/onvif/device_service"),
            OnvifAddress.candidates("http://192.168.4.91/"),
        )
    }

    @Test
    fun `ipv6 literal keeps its brackets`() {
        assertEquals(listOf("http://[fe80::1]:8000/onvif/device_service", "http://[fe80::1]:80/onvif/device_service"), OnvifAddress.candidates("[fe80::1]"))
    }

    @Test
    fun `garbage yields nothing`() {
        assertEquals(emptyList<String>(), OnvifAddress.candidates(""))
        assertEquals(emptyList<String>(), OnvifAddress.candidates("not a host!"))
        assertEquals(emptyList<String>(), OnvifAddress.candidates("host:99999"))
    }
}
