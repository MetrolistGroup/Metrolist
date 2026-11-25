package com.metrolist.shared.recognition

class NoRecognitionMatchException(
    message: String,
) : IllegalStateException(message)

class RecognitionUserException(
    message: String,
) : IllegalStateException(message)
