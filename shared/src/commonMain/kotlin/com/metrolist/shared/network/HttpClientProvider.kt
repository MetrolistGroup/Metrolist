package com.metrolist.shared.network

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
