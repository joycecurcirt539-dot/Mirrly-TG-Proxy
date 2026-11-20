package com.mirrly.tgproxy.ui.icon

import org.junit.Assert.assertTrue
import org.junit.Test

class PortConflictIconContractTest {

    @Test
    fun portConflictPathConstantIsValidSvgPathData() {
        assertTrue(MirrlyNotificationIcons.PATH_PORT_CONFLICT.isNotBlank())
        assertTrue(
            "PATH_PORT_CONFLICT must be valid SVG pathData",
            SvgPathDataParser.validatePathData(MirrlyNotificationIcons.PATH_PORT_CONFLICT)
        )
    }
}
