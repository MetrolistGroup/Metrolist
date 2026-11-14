/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceConnectCryptoTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun ecdhSharedSecretMatches() {
        if (!deviceConnectCryptoAvailable) return
        val a = DeviceCrypto.generateEcKeyPair()
        val b = DeviceCrypto.generateEcKeyPair()
        val sa = DeviceCrypto.deriveSharedSecret(a.privateKeyPkcs8, b.publicKeyUncompressed)
        val sb = DeviceCrypto.deriveSharedSecret(b.privateKeyPkcs8, a.publicKeyUncompressed)
        assertContentEquals(sa, sb)
        assertTrue(sa.isNotEmpty())
    }

    @Test
    fun hkdfMatchesRfc5869TestVector() {
        val output =
            DeviceCrypto.hkdfSha256(
                ikm = "0b".repeat(22).hexToBytes(),
                salt = "000102030405060708090a0b0c".hexToBytes(),
                info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            )

        assertContentEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf".hexToBytes(),
            output,
        )
    }

    @Test
    fun aesGcmRoundTrip() {
        if (!deviceConnectCryptoAvailable) return
        val key = DeviceCrypto.hkdfSha256("k".encodeToByteArray(), null, "i".encodeToByteArray())
        val plain = "secret payload".encodeToByteArray()
        val aad = deviceConnectAad(DcMessageTypes.PING, 1)
        val ct = DeviceCrypto.aesGcmEncrypt(key, plain, aad)
        assertTrue(ct.size > plain.size)
        val back = DeviceCrypto.aesGcmDecrypt(key, ct, aad)
        assertContentEquals(plain, back)
    }

    @Test
    fun aesGcmFailsWithWrongKey() {
        if (!deviceConnectCryptoAvailable) return
        val key1 = DeviceCrypto.hkdfSha256("a".encodeToByteArray(), null, "i".encodeToByteArray())
        val key2 = DeviceCrypto.hkdfSha256("b".encodeToByteArray(), null, "i".encodeToByteArray())
        val aad = deviceConnectAad(DcMessageTypes.PING, 1)
        val ct = DeviceCrypto.aesGcmEncrypt(key1, "x".encodeToByteArray(), aad)
        var threw = false
        try {
            DeviceCrypto.aesGcmDecrypt(key2, ct, aad)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun aesGcmRejectsChangedEnvelopeMetadata() {
        if (!deviceConnectCryptoAvailable) return
        val key = DeviceCrypto.hkdfSha256("k".encodeToByteArray(), null, "i".encodeToByteArray())
        val ciphertext =
            DeviceCrypto.aesGcmEncrypt(
                key,
                "payload".encodeToByteArray(),
                deviceConnectAad(DcMessageTypes.PING, 1),
            )

        assertFailsWith<Exception> {
            DeviceCrypto.aesGcmDecrypt(key, ciphertext, deviceConnectAad(DcMessageTypes.PONG, 1))
        }
        assertFailsWith<Exception> {
            DeviceCrypto.aesGcmDecrypt(key, ciphertext, deviceConnectAad(DcMessageTypes.PING, 2))
        }
    }

    @Test
    fun base64RoundTripsAndRejectsMalformedInput() {
        assertEquals("", ByteArray(0).toBase64())
        assertContentEquals(byteArrayOf(), "".fromBase64())
        assertEquals("aGVsbG8=", "hello".encodeToByteArray().toBase64())
        assertContentEquals("hello".encodeToByteArray(), "aGVsbG8=".fromBase64())
        assertFailsWith<IllegalArgumentException> { "aGV!sbG8=".fromBase64() }
        assertFailsWith<IllegalArgumentException> { "a===".fromBase64() }
        assertFailsWith<IllegalArgumentException> { "aGVsbG8".fromBase64() }
        assertFailsWith<IllegalArgumentException> { "Zh==".fromBase64() }
    }

    @Test
    fun heartbeatExpiresOnlyAfterTimeout() {
        assertFalse(isHeartbeatExpired(1_000, 21_000, 20_000))
        assertTrue(isHeartbeatExpired(1_000, 21_001, 20_000))
    }

    @Test
    fun transcriptIsCanonicalAndBindsBothHelloRoles() {
        val initiator =
            DeviceConnectHelloMaterial(
                role = DeviceConnectRole.INITIATOR,
                deviceId = "initiator",
                nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 1 },
                publicKey = ByteArray(33) { 2 },
            )
        val responder =
            DeviceConnectHelloMaterial(
                role = DeviceConnectRole.RESPONDER,
                deviceId = "responder",
                nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 3 },
                publicKey = ByteArray(33) { 4 },
            )

        val first = deviceConnectTranscriptHash(initiator, responder)
        val reversedInputs = deviceConnectTranscriptHash(responder, initiator)
        val changedNonce = deviceConnectTranscriptHash(initiator, responder.copy(nonce = ByteArray(16) { 5 }))

        assertContentEquals(first, reversedInputs)
        assertFalse(first.contentEquals(changedNonce))
    }

    @Test
    fun codePairingTranscriptBindsBothScopesCapabilitiesAndRolesWithoutLegacyProofText() {
        val hello = ByteArray(32) { 1 }
        val local =
            IdentityPayload("account", "legacy-proof-a", anonymous = false, resolved = true, hasPair = false, supportsCodePairing = true)
        val peer = local.copy(accountProof = "legacy-proof-b", hasPair = true)
        val transcript = deviceConnectCodePairingTranscript(hello, DeviceConnectRole.INITIATOR, local, peer)
        assertContentEquals(transcript, deviceConnectCodePairingTranscript(hello, DeviceConnectRole.RESPONDER, peer, local))
        assertContentEquals(
            transcript,
            deviceConnectCodePairingTranscript(hello, DeviceConnectRole.INITIATOR, local.copy(accountProof = "other-login"), peer),
        )
        val changes =
            listOf(
                peer.copy(fingerprint = "other-account"),
                peer.copy(anonymous = true),
                peer.copy(resolved = false),
                peer.copy(hasPair = false),
                peer.copy(supportsCodePairing = false),
            )
        for (changed in changes) {
            assertFalse(transcript.contentEquals(deviceConnectCodePairingTranscript(hello, DeviceConnectRole.INITIATOR, local, changed)))
        }
        assertFalse(
            transcript.contentEquals(deviceConnectCodePairingTranscript(ByteArray(32) { 2 }, DeviceConnectRole.INITIATOR, local, peer)),
        )
        assertFalse(transcript.contentEquals(deviceConnectCodePairingTranscript(hello, DeviceConnectRole.RESPONDER, local, peer)))
        val legacySecret = deviceConnectPairSecret(ByteArray(32) { 3 }, hello)
        assertFalse(legacySecret.contentEquals(deviceConnectPairSecret(legacySecret, transcript)))
    }

    @Test
    fun legacyIdentityWithoutCapabilityKeepsVersionTwoAndCredentialProof() {
        val payload =
            DcJson.decodeFromString<IdentityPayload>(
                """{"fingerprint":"account","accountProof":"proof","anonymous":false,"resolved":true,"hasPair":true}""",
            )
        assertFalse(payload.supportsCodePairing)
        assertEquals("proof", payload.accountProof)
        assertEquals(2, DEVICE_CONNECT_PROTOCOL_VERSION)
    }

    @Test
    fun helloCommitmentBindsRoleIdentityNonceAndKey() {
        val material =
            DeviceConnectHelloMaterial(
                role = DeviceConnectRole.INITIATOR,
                deviceId = "initiator",
                nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 1 },
                publicKey = ByteArray(33) { 2 },
            )
        val commitment = deviceConnectHelloCommitment(material)
        val encoded = commitment.toBase64()

        assertEquals(DEVICE_CONNECT_HELLO_COMMITMENT_BYTES, commitment.size)
        assertTrue(deviceConnectHelloCommitmentMatches(material, encoded))
        assertFalse(deviceConnectHelloCommitmentMatches(material.copy(role = DeviceConnectRole.RESPONDER), encoded))
        assertFalse(deviceConnectHelloCommitmentMatches(material.copy(deviceId = "other"), encoded))
        assertFalse(
            deviceConnectHelloCommitmentMatches(
                material.copy(nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES) { 3 }),
                encoded,
            ),
        )
        assertFalse(deviceConnectHelloCommitmentMatches(material.copy(publicKey = ByteArray(33) { 4 }), encoded))
        assertFalse(deviceConnectHelloCommitmentMatches(material, byteArrayOf(1).toBase64()))
        assertFalse(deviceConnectHelloCommitmentMatches(material, encoded.removeSuffix("=")))
    }

    @Test
    fun deviceIdsAcceptOnlyBoundedAsciiTokens() {
        assertTrue(isValidDeviceId("abc-DEF_123"))
        assertFalse(isValidDeviceId(""))
        assertFalse(isValidDeviceId("device/id"))
        assertFalse(isValidDeviceId("dévice"))
    }

    @Test
    fun helloCommitAndRevealValidationRejectV1SelfAndReflection() {
        val material =
            DeviceConnectHelloMaterial(
                role = DeviceConnectRole.RESPONDER,
                deviceId = "remote",
                nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES),
                publicKey = byteArrayOf(1),
            )
        val commit =
            HelloCommitPayload(
                version = DEVICE_CONNECT_PROTOCOL_VERSION,
                role = material.role.wireName,
                deviceId = material.deviceId,
                commitment = deviceConnectHelloCommitment(material).toBase64(),
            )
        val valid =
            HelloPayload(
                version = DEVICE_CONNECT_PROTOCOL_VERSION,
                role = material.role.wireName,
                deviceId = material.deviceId,
                nonce = material.nonce.toBase64(),
                publicKey = material.publicKey.toBase64(),
            )

        assertTrue(
            isValidRemoteHelloCommit(
                commit = commit,
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
                expectedDiscoveredDeviceId = "remote",
            ),
        )
        assertFalse(
            isValidRemoteHelloCommit(
                commit = commit.copy(version = 1),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertFalse(
            isValidRemoteHelloCommit(
                commit = commit.copy(deviceId = "local"),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertFalse(
            isValidRemoteHelloCommit(
                commit = commit.copy(role = DeviceConnectRole.INITIATOR.wireName),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertTrue(
            isValidRemoteHello(
                hello = valid,
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
                expectedDiscoveredDeviceId = "remote",
            ),
        )
        assertFalse(
            isValidRemoteHello(
                hello = valid.copy(version = 1),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertFalse(
            isValidRemoteHello(
                hello = valid.copy(deviceId = "local"),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertFalse(
            isValidRemoteHello(
                hello = valid.copy(role = DeviceConnectRole.INITIATOR.wireName),
                expectedRole = DeviceConnectRole.RESPONDER,
                localDeviceId = "local",
            ),
        )
        assertTrue(isNewDeviceConnectSequence(1, -1))
        assertFalse(isNewDeviceConnectSequence(1, 1))
        assertFalse(isNewDeviceConnectSequence(0, -1))
        assertTrue(isDeviceConnectPairingMessage(DcMessageTypes.PAIR_REQUEST))
        assertTrue(isDeviceConnectPairingMessage(DcMessageTypes.PAIR_APPROVE))
        assertFalse(isDeviceConnectPairingMessage(DcMessageTypes.IDENTITY))
        assertFalse(isDeviceConnectPairingMessage(DcMessageTypes.AUTH_RESULT))
        assertFalse(isDeviceConnectMessageAllowedBeforeIdentity(DcMessageTypes.PAIR_REQUEST, peerIdentityVerified = false))
        assertTrue(isDeviceConnectMessageAllowedBeforeIdentity(DcMessageTypes.PAIR_REQUEST, peerIdentityVerified = true))
        assertTrue(isDeviceConnectMessageAllowedBeforeIdentity(DcMessageTypes.IDENTITY, peerIdentityVerified = false))
    }

    @Test
    fun directionalKeysAndProofsRejectReflection() {
        val transcript = ByteArray(32) { 7 }
        val shared = ByteArray(32) { 9 }
        val initiatorKey = deviceConnectTrafficKey(shared, transcript, DeviceConnectRole.INITIATOR)
        val responderKey = deviceConnectTrafficKey(shared, transcript, DeviceConnectRole.RESPONDER)
        val proof =
            deviceConnectProof(
                secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 6 },
                phase = "pair-approve",
                senderRole = DeviceConnectRole.INITIATOR,
                senderDeviceId = "initiator",
                receiverDeviceId = "responder",
                transcriptHash = transcript,
            )

        assertFalse(initiatorKey.contentEquals(responderKey))
        assertTrue(
            deviceConnectProofMatches(
                secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 6 },
                proof = proof,
                phase = "pair-approve",
                senderRole = DeviceConnectRole.INITIATOR,
                senderDeviceId = "initiator",
                receiverDeviceId = "responder",
                transcriptHash = transcript,
            ),
        )
        assertFalse(
            deviceConnectProofMatches(
                secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 6 },
                proof = proof,
                phase = "pair-approve",
                senderRole = DeviceConnectRole.RESPONDER,
                senderDeviceId = "initiator",
                receiverDeviceId = "responder",
                transcriptHash = transcript,
            ),
        )
        assertFalse(
            deviceConnectProofMatches(
                secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 6 },
                proof = proof,
                phase = "pair-approve",
                senderRole = DeviceConnectRole.INITIATOR,
                senderDeviceId = "initiator",
                receiverDeviceId = "other",
                transcriptHash = transcript,
            ),
        )
        assertFalse(
            deviceConnectProofMatches(
                secret = "other-account".encodeToByteArray(),
                proof = proof,
                phase = "pair-approve",
                senderRole = DeviceConnectRole.INITIATOR,
                senderDeviceId = "initiator",
                receiverDeviceId = "responder",
                transcriptHash = transcript,
            ),
        )
    }

    @Test
    fun sasIsStableSixDigitsAndChangesWithTheDerivedSecret() {
        val first = deviceConnectSas(ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 1 })
        val second = deviceConnectSas(ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 1 })
        val different = deviceConnectSas(ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 2 })

        assertEquals(first, second)
        assertEquals(6, first.length)
        assertTrue(first.all(Char::isDigit))
        assertNotEquals(first, different)
    }

    @Test
    fun accountAndReconnectProofsRequireTheSameSecret() {
        val transcript = ByteArray(32) { 5 }
        val accountProof =
            deviceConnectProof(
                secret = "account-secret".encodeToByteArray(),
                phase = "identity|fingerprint|false|true|false",
                senderRole = DeviceConnectRole.INITIATOR,
                senderDeviceId = "initiator",
                receiverDeviceId = "responder",
                transcriptHash = transcript,
            )
        val reconnectProof =
            deviceConnectProof(
                secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 4 },
                phase = "reconnect",
                senderRole = DeviceConnectRole.RESPONDER,
                senderDeviceId = "responder",
                receiverDeviceId = "initiator",
                transcriptHash = transcript,
            )

        assertTrue(
            deviceConnectProofMatches(
                "account-secret".encodeToByteArray(),
                accountProof,
                "identity|fingerprint|false|true|false",
                DeviceConnectRole.INITIATOR,
                "initiator",
                "responder",
                transcript,
            ),
        )
        assertFalse(
            deviceConnectProofMatches(
                "other-account".encodeToByteArray(),
                accountProof,
                "identity|fingerprint|false|true|false",
                DeviceConnectRole.INITIATOR,
                "initiator",
                "responder",
                transcript,
            ),
        )
        assertTrue(
            deviceConnectProofMatches(
                ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 4 },
                reconnectProof,
                "reconnect",
                DeviceConnectRole.RESPONDER,
                "responder",
                "initiator",
                transcript,
            ),
        )
    }

    @Test
    fun pairingPathRequiresBothStoredGrants() {
        assertEquals(DeviceConnectPairingPath.RECONNECT, deviceConnectPairingPath(true, true))
        assertEquals(DeviceConnectPairingPath.NEW_PAIR, deviceConnectPairingPath(true, false))
        assertEquals(DeviceConnectPairingPath.NEW_PAIR, deviceConnectPairingPath(false, true))
        assertEquals(DeviceConnectPairingPath.NEW_PAIR, deviceConnectPairingPath(false, false))
    }

    @Test
    fun sessionIdentityGuardRejectsRealmChanges() {
        val signedIn = AccountIdentitySnapshot("secret-a", "fingerprint-a", anonymous = false, resolved = true)
        val otherAccount = AccountIdentitySnapshot("secret-b", "fingerprint-b", anonymous = false, resolved = true)
        val anonymous = AccountIdentitySnapshot("", "", anonymous = true, resolved = true)

        assertTrue(isCurrentDeviceConnectIdentity(signedIn, signedIn.copy()))
        assertFalse(isCurrentDeviceConnectIdentity(signedIn, otherAccount))
        assertFalse(isCurrentDeviceConnectIdentity(signedIn, anonymous))
    }

    @Test
    fun fingerprintsMatchForSameSecret() {
        assertEquals("2RyssyoEoI4Ek/IP7bvpvb0zzlcCIkYRt/7cSJBKYq8=", AccountIdentity.accountFingerprint("same"))
        assertNotEquals(
            AccountIdentity.accountFingerprint("a"),
            AccountIdentity.accountFingerprint("b"),
        )
    }

    @Test
    fun accountSnapshotTracksCredentialChanges() {
        val oldCookie = Settings.shared.innerTubeCookie
        val oldDataSyncId = Settings.shared.dataSyncId
        val oldEmail = Settings.shared.accountEmail
        val oldChannelHandle = Settings.shared.accountChannelHandle
        try {
            Settings.shared.innerTubeCookie = ""
            Settings.shared.dataSyncId = ""
            Settings.shared.accountEmail = ""
            Settings.shared.accountChannelHandle = ""
            val anonymous = AccountIdentity.snapshot()

            Settings.shared.innerTubeCookie = "SAPISID=test"
            Settings.shared.dataSyncId = "test-sync"
            val signedIn = AccountIdentity.snapshot()
            assertTrue(signedIn === AccountIdentity.snapshot())
            Settings.shared.innerTubeCookie = "SAPISID=rotated"
            val rotated = AccountIdentity.snapshot()
            assertNotEquals(signedIn.secret, rotated.secret)
            assertEquals(signedIn.fingerprint, rotated.fingerprint)
            Settings.shared.dataSyncId = "different-sync"
            assertNotEquals(rotated.fingerprint, AccountIdentity.snapshot().fingerprint)

            assertTrue(anonymous.anonymous)
            assertFalse(signedIn.anonymous)
            assertNotEquals(anonymous.fingerprint, signedIn.fingerprint)

            Settings.shared.innerTubeCookie = ""
            Settings.shared.dataSyncId = "stale-sync"
            assertTrue(AccountIdentity.snapshot().anonymous)
        } finally {
            Settings.shared.innerTubeCookie = oldCookie
            Settings.shared.dataSyncId = oldDataSyncId
            Settings.shared.accountEmail = oldEmail
            Settings.shared.accountChannelHandle = oldChannelHandle
        }
    }

    @Test
    fun legacyAccountProofMaterialBindsCredentialAndScope() {
        val oldCookie = Settings.shared.innerTubeCookie
        val oldDataSyncId = Settings.shared.dataSyncId
        val oldEmail = Settings.shared.accountEmail
        val oldChannelHandle = Settings.shared.accountChannelHandle
        try {
            Settings.shared.dataSyncId = "same-account"
            Settings.shared.accountEmail = ""
            Settings.shared.accountChannelHandle = ""
            Settings.shared.innerTubeCookie = "SAPISID=device-a"
            val first = AccountIdentity.snapshot()

            Settings.shared.innerTubeCookie = "SAPISID=device-b"
            val second = AccountIdentity.snapshot()

            assertEquals(first.fingerprint, second.fingerprint)
            assertNotEquals(first.secret, second.secret)

            Settings.shared.innerTubeCookie = "SAPISID=device-a"
            Settings.shared.dataSyncId = "other-account"
            val otherAccount = AccountIdentity.snapshot()

            assertNotEquals(first.fingerprint, otherAccount.fingerprint)
            assertNotEquals(first.secret, otherAccount.secret)
        } finally {
            Settings.shared.innerTubeCookie = oldCookie
            Settings.shared.dataSyncId = oldDataSyncId
            Settings.shared.accountEmail = oldEmail
            Settings.shared.accountChannelHandle = oldChannelHandle
        }
    }

    @Test
    fun accountScopeMatchesFullAndNormalizedDataSyncIdsAcrossDevices() {
        val oldCookie = Settings.shared.innerTubeCookie
        val oldDataSyncId = Settings.shared.dataSyncId
        try {
            Settings.shared.innerTubeCookie = "SAPISID=device-a"
            Settings.shared.dataSyncId = " channel-a||account-id "
            val full = AccountIdentity.snapshot()

            Settings.shared.dataSyncId = "channel-a"
            assertEquals(full, AccountIdentity.snapshot())

            Settings.shared.innerTubeCookie = "SAPISID=device-b"
            val otherDevice = AccountIdentity.snapshot()
            assertEquals(full.fingerprint, otherDevice.fingerprint)
            assertNotEquals(full.secret, otherDevice.secret)

            Settings.shared.dataSyncId = "channel-b||account-id"
            assertNotEquals(full.fingerprint, AccountIdentity.snapshot().fingerprint)

            Settings.shared.dataSyncId = "||account-id"
            val personalAccount = AccountIdentity.snapshot()
            Settings.shared.dataSyncId = "account-id"
            assertEquals(personalAccount, AccountIdentity.snapshot())
            assertNotEquals(full.fingerprint, personalAccount.fingerprint)

            Settings.shared.dataSyncId = " || "
            assertFalse(AccountIdentity.snapshot().resolved)
        } finally {
            Settings.shared.innerTubeCookie = oldCookie
            Settings.shared.dataSyncId = oldDataSyncId
        }
    }

    @Test
    fun cookieOnlyAccountIsNotAnonymous() {
        val oldCookie = Settings.shared.innerTubeCookie
        val oldDataSyncId = Settings.shared.dataSyncId
        val oldEmail = Settings.shared.accountEmail
        val oldChannelHandle = Settings.shared.accountChannelHandle
        try {
            Settings.shared.dataSyncId = ""
            Settings.shared.accountEmail = ""
            Settings.shared.accountChannelHandle = ""
            Settings.shared.innerTubeCookie = "SAPISID=signed-in-but-unresolved"

            val unresolved = AccountIdentity.snapshot()
            assertFalse(unresolved.anonymous)
            assertFalse(unresolved.resolved)
            assertEquals("", unresolved.fingerprint)
        } finally {
            Settings.shared.innerTubeCookie = oldCookie
            Settings.shared.dataSyncId = oldDataSyncId
            Settings.shared.accountEmail = oldEmail
            Settings.shared.accountChannelHandle = oldChannelHandle
        }
    }

    @Test
    fun emailAloneIsNotUsedAsAccountProof() {
        val oldCookie = Settings.shared.innerTubeCookie
        val oldDataSyncId = Settings.shared.dataSyncId
        val oldEmail = Settings.shared.accountEmail
        try {
            Settings.shared.innerTubeCookie = ""
            Settings.shared.dataSyncId = ""
            Settings.shared.accountEmail = "person@example.com"

            assertTrue(AccountIdentity.snapshot().anonymous)
        } finally {
            Settings.shared.innerTubeCookie = oldCookie
            Settings.shared.dataSyncId = oldDataSyncId
            Settings.shared.accountEmail = oldEmail
        }
    }

    @Test
    fun protocolSerializationRoundTrip() {
        val commit =
            HelloCommitPayload(
                version = DEVICE_CONNECT_PROTOCOL_VERSION,
                role = DeviceConnectRole.INITIATOR.wireName,
                deviceId = "dev1",
                commitment = ByteArray(DEVICE_CONNECT_HELLO_COMMITMENT_BYTES).toBase64(),
            )
        assertEquals(
            commit,
            decodePayload(DcMessageTypes.HELLO_COMMIT, DcJson.encodeToString(commit)) as HelloCommitPayload,
        )

        val hello = DcWire(DcMessageTypes.HELLO, "cHVi", version = DEVICE_CONNECT_PROTOCOL_VERSION, encrypted = false)
        val json = DcJson.encodeToString(hello)
        val back = DcJson.decodeFromString<DcWire>(json)
        assertEquals(hello.type, back.type)
        assertEquals(hello.payload, back.payload)

        assertEquals(0, DcWire(DcMessageTypes.HELLO, "cHVi").version)

        val proof = PairProofPayload(deviceId = "dev1", proof = "cHJvb2Y=")
        val inner = DcJson.encodeToString(proof)
        val decoded = decodePayload(DcMessageTypes.PAIR_PROOF, inner) as PairProofPayload
        assertEquals("dev1", decoded.deviceId)
    }

    @Test
    fun deviceTypesUseStableWireNamesAndUnknownFallbacks() {
        val expected =
            mapOf(
                DeviceConnectDeviceType.ANDROID_PHONE to "android_phone",
                DeviceConnectDeviceType.DESKTOP to "desktop",
                DeviceConnectDeviceType.ANDROID_AUTO to "android_auto",
                DeviceConnectDeviceType.WEAR_OS to "wear_os",
                DeviceConnectDeviceType.IOS to "ios",
                DeviceConnectDeviceType.UNKNOWN to "unknown",
            )

        expected.forEach { (type, wireName) ->
            assertEquals(wireName, type.wireName)
            assertEquals(type, DeviceConnectDeviceType.fromWireName(wireName))
        }
        listOf(null, "", "ANDROID_PHONE", "vision_os").forEach { wireName ->
            assertEquals(DeviceConnectDeviceType.UNKNOWN, DeviceConnectDeviceType.fromWireName(wireName))
        }

        val legacy =
            DcJson.decodeFromString<DeviceInfoPayload>(
                """{"deviceId":"old-peer","deviceName":"Old","model":"Test"}""",
            )
        assertEquals(DeviceConnectDeviceType.UNKNOWN.wireName, legacy.deviceType)
        assertEquals(
            DeviceConnectDeviceType.DESKTOP,
            selectDeviceConnectDeviceType(
                stored = DeviceConnectDeviceType.DESKTOP,
                advertised = DeviceConnectDeviceType.ANDROID_PHONE,
            ),
        )
        assertEquals(
            DeviceConnectDeviceType.ANDROID_PHONE,
            selectDeviceConnectDeviceType(advertised = DeviceConnectDeviceType.ANDROID_PHONE),
        )
        assertEquals(
            DeviceConnectDeviceType.WEAR_OS,
            selectDeviceConnectDeviceType(
                authenticated = DeviceConnectDeviceType.WEAR_OS,
                stored = DeviceConnectDeviceType.DESKTOP,
                advertised = DeviceConnectDeviceType.ANDROID_PHONE,
            ),
        )
        assertEquals(
            DeviceConnectDeviceType.DESKTOP,
            selectDeviceConnectDeviceType(
                authenticated = DeviceConnectDeviceType.UNKNOWN,
                stored = DeviceConnectDeviceType.DESKTOP,
                advertised = DeviceConnectDeviceType.ANDROID_PHONE,
            ),
        )
        assertEquals(
            DeviceConnectDeviceType.UNKNOWN,
            selectDeviceConnectDeviceType(),
        )
    }

    @Test
    fun discoveryTxtRecordsRoundTripDisplayMetadataAndHandleMissingValues() {
        val records =
            deviceConnectDiscoveryTxtRecords(
                "peer",
                "  Living\u0000   Room  ",
                DeviceConnectDeviceType.ANDROID_PHONE,
            )

        assertEquals(setOf("deviceId", "deviceName", "deviceType"), records.keys)
        assertEquals("peer", records[DEVICE_CONNECT_DEVICE_ID_TXT_KEY])
        assertEquals("Living Room", deviceConnectNameFromTxtRecord(records::get))
        assertEquals(DeviceConnectDeviceType.ANDROID_PHONE, deviceConnectTypeFromTxtRecord(records::get))
        val splitPairName = "a".repeat(47) + "\uD83D\uDE80"
        val completePairName = "a".repeat(46) + "\uD83D\uDE80"
        assertEquals(
            "a".repeat(47),
            deviceConnectDiscoveryTxtRecords("peer", splitPairName, DeviceConnectDeviceType.DESKTOP)
                .getValue(DEVICE_CONNECT_DEVICE_NAME_TXT_KEY),
        )
        assertEquals(
            completePairName,
            deviceConnectDiscoveryTxtRecords("peer", completePairName, DeviceConnectDeviceType.DESKTOP)
                .getValue(DEVICE_CONNECT_DEVICE_NAME_TXT_KEY),
        )
        assertEquals("", deviceConnectNameFromTxtRecord(emptyMap<String, String>()::get))
        assertEquals(DeviceConnectDeviceType.UNKNOWN, deviceConnectTypeFromTxtRecord(emptyMap<String, String>()::get))
    }

    @Test
    fun helloCarriesOnlyTranscriptMaterial() {
        val hello =
            HelloPayload(
                version = DEVICE_CONNECT_PROTOCOL_VERSION,
                role = DeviceConnectRole.INITIATOR.wireName,
                deviceId = "device",
                nonce = ByteArray(DEVICE_CONNECT_HELLO_NONCE_BYTES).toBase64(),
                publicKey = byteArrayOf(1).toBase64(),
            )

        val json = DcJson.encodeToString(hello)
        assertFalse(json.contains("deviceName"))
        assertFalse(json.contains("model"))
        assertFalse(json.contains("deviceType"))
        assertEquals(hello, DcJson.decodeFromString<HelloPayload>(json))

        val commit =
            HelloCommitPayload(
                version = hello.version,
                role = hello.role,
                deviceId = hello.deviceId,
                commitment =
                    deviceConnectHelloCommitment(
                        DeviceConnectHelloMaterial(
                            DeviceConnectRole.INITIATOR,
                            hello.deviceId,
                            hello.nonce.fromBase64(),
                            hello.publicKey.fromBase64(),
                        ),
                    ).toBase64(),
            )
        val commitJson = DcJson.encodeToString(commit)
        assertFalse(commitJson.contains("nonce"))
        assertFalse(commitJson.contains("publicKey"))
    }

    @Test
    fun pairStoreRejectsUnknownSchemaAndInvalidSecrets() {
        val settings = Settings(MapSettings())
        val store = DevicePairStore(settings)
        val identity = AccountIdentitySnapshot("account", "fingerprint", anonymous = false, resolved = true)
        val secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 4 }

        store.save("peer", "Peer", "Model", identity, secret)
        assertContentEquals(secret, store.find("peer", identity)?.secret)

        val validSecret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 3 }.toBase64()
        val unknownStore =
            """{"version":2,"pairs":[{"peerDeviceId":"peer","peerDeviceName":"Peer","peerModel":"Model","accountFingerprint":"fingerprint","anonymous":false,"secret":"$validSecret"}]}"""
        settings.deviceConnectPairedDevices = unknownStore
        assertEquals(null, store.find("peer", identity))
        assertFailsWith<IllegalStateException> {
            store.save("peer", "Peer", "Model", identity, secret)
        }
        assertEquals(unknownStore, settings.deviceConnectPairedDevices)

        val corruptStore =
            """{"version":1,"pairs":[{"peerDeviceId":"peer","peerDeviceName":"Peer","peerModel":"Model","accountFingerprint":"fingerprint","anonymous":false,"secret":"AQ=="}]}"""
        settings.deviceConnectPairedDevices = corruptStore
        assertEquals(null, store.find("peer", identity))
        assertFailsWith<IllegalStateException> { store.forget("peer", identity) }
        assertEquals(corruptStore, settings.deviceConnectPairedDevices)
        assertFailsWith<IllegalArgumentException> {
            store.save("peer", "Peer", "Model", identity, byteArrayOf(1))
        }
    }

    @Test
    fun pairStoreScopesGrantsByResolvedAccountAndAnonymousRealm() {
        val settings = Settings(MapSettings())
        val store = DevicePairStore(settings)
        val accountA = AccountIdentitySnapshot("a", "account-a", anonymous = false, resolved = true)
        val accountB = AccountIdentitySnapshot("b", "account-b", anonymous = false, resolved = true)
        val anonymous = AccountIdentitySnapshot("", "", anonymous = true, resolved = true)
        val unresolved = AccountIdentitySnapshot("unresolved", "", anonymous = false, resolved = false)
        val secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 8 }

        store.save("peer", "Peer", "", accountA, secret)
        store.save("peer", "Peer B", "", accountB, secret)
        assertContentEquals(secret, store.find("peer", accountA)?.secret)
        assertContentEquals(secret, store.find("peer", accountB)?.secret)
        assertEquals(null, store.find("peer", anonymous))
        assertEquals(null, store.find("peer", unresolved))

        store.save("anonymous-peer", "Anonymous", "", anonymous, secret)
        assertContentEquals(secret, store.find("anonymous-peer", anonymous)?.secret)
        assertEquals(null, store.find("anonymous-peer", accountA))

        assertTrue(store.updateMetadata("peer", "Verified Peer", "Verified Model", accountA))
        assertEquals("Verified Peer", store.find("peer", accountA)?.peerDeviceName)
        assertEquals("Verified Model", store.find("peer", accountA)?.peerModel)

        val replacementSecret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 9 }
        store.save("peer", "Replacement", "", accountA, replacementSecret)
        assertFalse(store.forgetIfSecret("peer", accountA, secret))
        assertContentEquals(replacementSecret, store.find("peer", accountA)?.secret)
        assertTrue(store.forgetIfSecret("peer", accountA, replacementSecret))
        assertEquals(null, store.find("peer", accountA))

        store.save("peer", "Peer", "", accountA, secret)
        store.forget("peer", accountA)
        assertEquals(null, store.find("peer", accountA))
        assertContentEquals(secret, store.find("peer", accountB)?.secret)
    }

    @Test
    fun pairStoreMigratesAndPreservesAuthenticatedDeviceTypeMetadata() {
        val settings = Settings(MapSettings())
        val identity = AccountIdentitySnapshot("account", "fingerprint", anonymous = false, resolved = true)
        val secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 7 }
        settings.deviceConnectPairedDevices =
            """{"version":1,"pairs":[{"peerDeviceId":"peer","peerDeviceName":"Peer","peerModel":"Model","accountFingerprint":"fingerprint","anonymous":false,"secret":"${secret.toBase64()}"}]}"""

        val store = DevicePairStore(settings)
        assertEquals(DeviceConnectDeviceType.UNKNOWN, store.find("peer", identity)?.peerDeviceType)
        assertTrue(
            store.updateMetadata(
                peerDeviceId = "peer",
                peerDeviceName = "Verified Peer",
                peerModel = "Verified Model",
                identity = identity,
                peerDeviceType = DeviceConnectDeviceType.DESKTOP,
            ),
        )
        assertEquals(DeviceConnectDeviceType.DESKTOP, DevicePairStore(settings).find("peer", identity)?.peerDeviceType)

        assertFalse(
            store.updateMetadata(
                peerDeviceId = "peer",
                peerDeviceName = "Verified Peer",
                peerModel = "Verified Model",
                identity = identity,
                peerDeviceType = DeviceConnectDeviceType.UNKNOWN,
            ),
        )
        assertEquals(DeviceConnectDeviceType.DESKTOP, store.find("peer", identity)?.peerDeviceType)

        store.save(
            peerDeviceId = "peer",
            peerDeviceName = "Re-paired Peer",
            peerModel = "Re-paired Model",
            identity = identity,
            secret = ByteArray(DEVICE_CONNECT_PAIR_SECRET_BYTES) { 8 },
        )
        assertEquals(DeviceConnectDeviceType.DESKTOP, store.find("peer", identity)?.peerDeviceType)
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
