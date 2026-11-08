package com.notcode.mobile

import com.notcode.mobile.model.ProviderProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProjectApprovalPolicyTest {
    @Test fun trustAppliesOnlyUntilProjectChangesOrReopens() {
        val policy = ProjectApprovalPolicy()
        policy.openProject("first")
        assertFalse(policy.trusted)
        policy.grant()
        assertTrue(policy.trusted)
        policy.openProject("second")
        assertFalse(policy.trusted)
        policy.grant()
        policy.openProject("second")
        assertFalse(policy.trusted)
        policy.grant()
        policy.reset()
        assertFalse(policy.trusted)
    }

    @Test fun optionalModelNameFallsBackToId() {
        val profile = ProviderProfile(baseUrl = "https://example.com/v1", modelId = "model-id")
        assertEquals("model-id", profile.title)
        assertEquals("Моя модель", profile.copy(displayName = "Моя модель").title)
    }

    @Test fun olderProfilesRemainReadable() {
        val old = """{"id":"old","displayName":"Old model","baseUrl":"https://example.com/v1","modelId":"model"}"""
        val decoded = Json.decodeFromString(ProviderProfile.serializer(), old)
        assertEquals("Old model", decoded.title)
        assertEquals("", decoded.providerName)
    }
}
