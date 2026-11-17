package com.metrolist.shared.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class SonosZoneGroupsTest {
    @Test
    fun groupsListOnlyTheirCoordinatorAndHideBondedSpeakers() {
        val state =
            """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1">
              <ZoneGroupMember UUID="RINCON_B" Location="http://192.168.1.11:1400/xml/device_description.xml" ZoneName="Living Room"/>
              <ZoneGroupMember UUID="RINCON_A" Location="http://192.168.1.10:1400/xml/device_description.xml" ZoneName="Kitchen"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:2">
              <ZoneGroupMember UUID="RINCON_C" Location="http://192.168.1.12:1400/xml/device_description.xml" ZoneName="Bedroom"/>
              <ZoneGroupMember UUID="RINCON_D" Location="http://192.168.1.13:1400/xml/device_description.xml" ZoneName="Bedroom" Invisible="1"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_E" ID="RINCON_E:3">
              <ZoneGroupMember UUID="RINCON_E" Location="http://192.168.1.14:1400/xml/device_description.xml" ZoneName="TV Room">
                <Satellite UUID="RINCON_F" Location="http://192.168.1.15:1400/xml/device_description.xml" ZoneName="TV Room" Invisible="1"/>
              </ZoneGroupMember>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_G" ID="RINCON_G:4">
              <ZoneGroupMember UUID="RINCON_G" Location="http://192.168.1.16:1400/xml/device_description.xml" ZoneName="BOOST" Invisible="1"/>
            </ZoneGroup>
            </ZoneGroups><VanishedDevices/></ZoneGroupState>"""

        assertEquals(
            listOf(
                SonosZone("uuid:RINCON_A", "Living Room + Kitchen", "http://192.168.1.10:1400/xml/device_description.xml"),
                SonosZone("uuid:RINCON_C", "Bedroom", "http://192.168.1.12:1400/xml/device_description.xml"),
                SonosZone("uuid:RINCON_E", "TV Room", "http://192.168.1.14:1400/xml/device_description.xml"),
            ),
            parseSonosZoneGroups(state),
        )
    }
}
