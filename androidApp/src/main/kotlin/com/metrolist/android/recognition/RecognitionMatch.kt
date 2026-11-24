package com.metrolist.android.recognition

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.matchesRecognition as sharedMatchesRecognition

internal fun SongItem.matchesRecognition(result: RecognitionResult): Boolean = sharedMatchesRecognition(result)
