/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KizzyRepositoryImpl.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.repository

import com.my.kizzy.remote.ApiService
import com.my.kizzy.remote.ImageProxyResponse
import io.ktor.client.call.body

/**
 * Modified by Zion Huang
 */
class KizzyRepository(
    userAgent: String,
    superProperties: String?
) {
    private val api = ApiService(userAgent, superProperties)

    suspend fun getImages(urls: List<String>): ImageProxyResponse? {
        return api.getImage(urls).getOrNull()?.body()
    }
}
