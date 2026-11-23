// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {

    @Test
    fun `parses spdx and unknown licenses sorted with coordinate fallback`() {
        val rawJson = """
            [
              {
                "groupId": "com.squareup.okhttp3",
                "artifactId": "okhttp",
                "version": "5.3.0",
                "name": "okhttp",
                "spdxLicenses": [
                  {
                    "identifier": "Apache-2.0",
                    "name": "Apache License 2.0",
                    "url": "https://www.apache.org/licenses/LICENSE-2.0"
                  }
                ]
              },
              {
                "groupId": "com.google.android.play",
                "artifactId": "app-update",
                "version": "2.1.0",
                "unknownLicenses": [
                  {
                    "name": "Play Core Software Development Kit Terms of Service",
                    "url": "https://developer.android.com/guide/playcore/license"
                  }
                ]
              },
              {
                "groupId": "androidx.activity",
                "artifactId": "activity",
                "version": "1.12.2",
                "name": "Activity",
                "spdxLicenses": [
                  {
                    "identifier": "Apache-2.0",
                    "name": "Apache License 2.0"
                  }
                ]
              }
            ]
        """.trimIndent()

        val notices = parseLicenseeArtifacts(rawJson)
        assertEquals(3, notices.size)

        assertEquals("Activity", notices[0].displayName)
        assertEquals("androidx.activity:activity:1.12.2", notices[0].coordinates)
        assertEquals("Apache License 2.0", notices[0].licenseSummary)

        assertEquals("com.google.android.play:app-update", notices[1].displayName)
        assertEquals("com.google.android.play:app-update:2.1.0", notices[1].coordinates)
        assertEquals("Play Core Software Development Kit Terms of Service", notices[1].licenseSummary)

        assertEquals("okhttp", notices[2].displayName)
        assertEquals("com.squareup.okhttp3:okhttp:5.3.0", notices[2].coordinates)
    }

    @Test
    fun `handles empty or malformed json gracefully`() {
        assertTrue(parseLicenseeArtifacts("").isEmpty())
        assertTrue(parseLicenseeArtifacts("   ").isEmpty())
        assertTrue(parseLicenseeArtifacts("{not valid json").isEmpty())
    }
}
