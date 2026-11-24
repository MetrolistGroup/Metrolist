package com.metrolist.shared.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter

private const val RECOGNITION_COMPLETED = "com.metrolist.recognition.completed"
private const val RECOGNITION_REQUESTED = "com.metrolist.recognition.requested"

@OptIn(ExperimentalForeignApi::class)
object RecognitionNotification {
    val requestedNotificationName: String = RECOGNITION_REQUESTED
    val completedNotificationName: String = RECOGNITION_COMPLETED

    fun postRequested() {
        NSNotificationCenter.defaultCenter.postNotificationName(RECOGNITION_REQUESTED, `object` = null)
    }

    fun postCompleted(
        payload: RecognitionPayload,
        entryPoint: RecognitionEntryPoint,
        historySaved: Boolean,
    ) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            RECOGNITION_COMPLETED,
            `object` = null,
            userInfo = RecognitionWidgetTimeline.userInfo(payload, entryPoint, historySaved),
        )
    }
}
