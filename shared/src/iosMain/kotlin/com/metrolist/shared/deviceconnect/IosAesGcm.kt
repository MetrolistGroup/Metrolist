package com.metrolist.shared.deviceconnect

import com.metrolist.shared.deviceconnect.crypto.CCCryptorGCMOneshotDecrypt
import com.metrolist.shared.deviceconnect.crypto.CCCryptorGCMOneshotEncrypt
import com.metrolist.shared.deviceconnect.crypto.kCCAlgorithmAES
import com.metrolist.shared.deviceconnect.crypto.kCCSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal object IosAesGcm {
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 16

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Unsupported AES key size" }
        val iv = DeviceCrypto.secureRandomBytes(GCM_IV_LEN)
        val ciphertext = ByteArray(plaintext.size)
        val tag = ByteArray(GCM_TAG_LEN)
        val inputBuffer = if (plaintext.isEmpty()) byteArrayOf(0) else plaintext
        val outputBuffer = if (ciphertext.isEmpty()) byteArrayOf(0) else ciphertext
        val status =
            key.usePinned { keyPinned ->
                inputBuffer.usePinned { plainPinned ->
                    outputBuffer.usePinned { cipherPinned ->
                        associatedData.usePinned { aadPinned ->
                            iv.usePinned { ivPinned ->
                                tag.usePinned { tagPinned ->
                                    CCCryptorGCMOneshotEncrypt(
                                        alg = kCCAlgorithmAES.convert(),
                                        key = keyPinned.addressOf(0),
                                        keyLength = key.size.convert(),
                                        iv = ivPinned.addressOf(0),
                                        ivLen = iv.size.convert(),
                                        aData = if (associatedData.isEmpty()) null else aadPinned.addressOf(0),
                                        aDataLen = associatedData.size.convert(),
                                        dataIn = plainPinned.addressOf(0),
                                        dataInLength = plaintext.size.convert(),
                                        dataOut = cipherPinned.addressOf(0),
                                        tag = tagPinned.addressOf(0),
                                        tagLength = GCM_TAG_LEN.convert(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        check(status == kCCSuccess.toInt()) { "AES-GCM encrypt failed: $status" }
        return iv + ciphertext + tag
    }

    fun decrypt(
        key: ByteArray,
        data: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Unsupported AES key size" }
        require(data.size >= GCM_IV_LEN + GCM_TAG_LEN) { "ciphertext too short" }
        val iv = data.copyOfRange(0, GCM_IV_LEN)
        val tag = data.copyOfRange(data.size - GCM_TAG_LEN, data.size)
        val ciphertext = data.copyOfRange(GCM_IV_LEN, data.size - GCM_TAG_LEN)
        val plaintext = ByteArray(ciphertext.size)
        val inputBuffer = if (ciphertext.isEmpty()) byteArrayOf(0) else ciphertext
        val outputBuffer = if (plaintext.isEmpty()) byteArrayOf(0) else plaintext
        val status =
            key.usePinned { keyPinned ->
                inputBuffer.usePinned { cipherPinned ->
                    outputBuffer.usePinned { plainPinned ->
                        associatedData.usePinned { aadPinned ->
                            iv.usePinned { ivPinned ->
                                tag.usePinned { tagPinned ->
                                    CCCryptorGCMOneshotDecrypt(
                                        alg = kCCAlgorithmAES.convert(),
                                        key = keyPinned.addressOf(0),
                                        keyLength = key.size.convert(),
                                        iv = ivPinned.addressOf(0),
                                        ivLen = iv.size.convert(),
                                        aData = if (associatedData.isEmpty()) null else aadPinned.addressOf(0),
                                        aDataLen = associatedData.size.convert(),
                                        dataIn = cipherPinned.addressOf(0),
                                        dataInLength = ciphertext.size.convert(),
                                        dataOut = plainPinned.addressOf(0),
                                        tag = tagPinned.addressOf(0),
                                        tagLength = GCM_TAG_LEN.convert(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        check(status == kCCSuccess.toInt()) { "AES-GCM decrypt failed: $status" }
        return plaintext.copyOf(ciphertext.size)
    }
}
