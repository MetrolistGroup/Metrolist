package com.metrolist.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeAccountParserTest {
    private val json = Json

    @Test
    fun blankOptionalTokensDoNotMergeDifferentChannels() {
        val response =
            """
            {"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{"contents":[
              {"accountItemRenderer":{
                "accountName":{"runs":[{"text":"Personal"}]},"isSelected":true,
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"pageIdToken":{"pageId":""}},{"datasyncIdToken":{"datasyncIdToken":"||personal"}}
                ]}}
              }},
              {"accountItemRenderer":{
                "accountName":{"runs":[{"text":"Brand"}]},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"pageIdToken":{"pageId":""}},{"datasyncIdToken":{"datasyncIdToken":"brand||personal"}}
                ]}}
              }}
            ]}}}}]}
            """.trimIndent()

        val accounts = parseAccountsListResponse(response)

        assertEquals(listOf("Personal", "Brand"), accounts.map { it.name })
        assertEquals(listOf("||personal", "brand||personal"), accounts.map { it.dataSyncId })
    }

    @Test
    fun nullOrMalformedOptionalFieldsDoNotDiscardTheOtherChannels() {
        val accounts =
            parseAccountsListResponse(
                """
                {"contents":[
                  {"accountItem":null},
                  {"accountItemRenderer":{
                    "accountName":{"simpleText":"Personal"},"accountPhoto":null,"channelHandle":null,
                    "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                      null,{"pageIdToken":null},{"datasyncIdToken":{"datasyncIdToken":"||personal"}}
                    ]}}
                  }},
                  {"accountItemRenderer":{
                    "accountName":{"runs":[{"text":"Brand "},{"text":"channel"}]},"accountByline":[],
                    "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                      {"pageIdToken":{"pageId":"brand"}}
                    ]}}
                  }}
                ]}
                """.trimIndent(),
            )

        assertEquals(listOf("Personal", "Brand channel"), accounts.map { it.name })
        assertEquals("||personal", accounts.first().dataSyncId)
        assertEquals("brand", accounts.last().pageId)
    }

    @Test
    fun repeatedChannelsStillMergeWhenTheirOptionalTokensAreBlank() {
        val accounts =
            parseAccountsListResponse(
                """
                {"contents":[
                  {"accountItem":{"accountName":{"simpleText":"Personal"},"serviceEndpoint":{
                    "selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":""}}]}
                  }}},
                  {"accountItemRenderer":{"accountName":{"simpleText":"Personal"}}},
                  {"accountItemRenderer":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{
                    "selectActiveIdentityEndpoint":{"supportedTokens":[{"datasyncIdToken":{"datasyncIdToken":""}}]}
                  }}}
                ]}
                """.trimIndent(),
            )

        assertEquals(listOf("Personal", "Brand"), accounts.map { it.name })
    }

    @Test
    fun accountMenuFallbackParsesProfileFields() {
        val response =
            json
                .parseToJsonElement(
                    """
                    {
                      "actions": [{
                        "openPopupAction": {"popup": {"multiPageMenuRenderer": {
                          "header": {"activeAccountHeaderRenderer": {
                            "accountName": {"runs": [{"text": "Music account"}]},
                            "email": {"runs": [{"text": "user@example.invalid"}]},
                            "channelHandle": {"runs": [{"text": "@music"}]},
                            "accountPhoto": {"thumbnails": [{"url": "small"}, {"url": "large"}]}
                          }}
                        }}}
                      }]
                    }
                    """.trimIndent(),
                ).jsonObject

        assertEquals(
            "Music account",
            response.parseAccountInfoFallback()?.name,
        )
        assertEquals("user@example.invalid", response.parseAccountInfoFallback()?.email)
        assertEquals("@music", response.parseAccountInfoFallback()?.channelHandle)
        assertEquals("large", response.parseAccountInfoFallback()?.thumbnailUrl)
    }

    @Test
    fun accountsListFallbackFindsPopupAndPageItems() {
        val response =
            json
                .parseToJsonElement(
                    """
                    {
                      "actions": [
                        {
                          "getMultiPageMenuAction": {
                            "menu": {
                              "multiPageMenuRenderer": {
                                "sections": [
                                  {
                                    "accountSectionListRenderer": {
                                      "contents": [
                                        {
                                          "accountItemSectionRenderer": {
                                            "contents": [
                                              {
                                                "accountItem": {
                                                  "accountName": {"simpleText": "Main"},
                                                  "isSelected": true,
                                                  "serviceEndpoint": {
                                                    "selectActiveIdentityEndpoint": {
                                                      "supportedTokens": [{"pageIdToken": {"pageId": "page-1"}}]
                                                    }
                                                  }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        },
                        {
                          "updateChannelSwitcherPageAction": {
                            "page": {
                              "channelSwitcherPageRenderer": {
                                "contents": [
                                  {
                                    "accountItemRenderer": {
                                      "accountName": {"runs": [{"text": "Brand"}]},
                                      "channelHandle": {"runs": [{"text": "@brand"}]}
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ).jsonObject

        val accounts = response.parseAccountsListFallback()

        assertEquals(listOf("Main", "Brand"), accounts.map { it.name })
        assertEquals("page-1", accounts.first().pageId)
        assertEquals("@brand", accounts.last().channelHandle)
        assertEquals(true, accounts.first().isSelected)
    }

    @Test
    fun accountMenuFallbackReturnsNullWithoutActiveHeader() {
        assertNull(json.parseToJsonElement("{\"actions\": []}").jsonObject.parseAccountInfoFallback())
    }

    @Test
    fun accountMenuRejectsMissingOrBlankSourceName() {
        assertNull(
            parseAccountInfoResponse(
                """
                {
                  "actions": [{
                    "openPopupAction": {"popup": {"multiPageMenuRenderer": {
                      "header": {"activeAccountHeaderRenderer": {
                        "email": {"runs": [{"text": "user@example.invalid"}]}
                      }}
                    }}}
                  }]
                }
                """.trimIndent(),
            ),
        )
        assertNull(
            parseAccountInfoResponse(
                """
                {"activeAccountHeaderRenderer": {"accountName": {"runs": [{"text": ""}]}}}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun accountMenuAcceptsLiteralUnknownSourceName() {
        assertEquals(
            "Unknown",
            parseAccountInfoResponse(
                """
                {"activeAccountHeaderRenderer": {"accountName": {"runs": [{"text": "Unknown"}]}}}
                """.trimIndent(),
            )?.name,
        )
    }

    @Test
    fun accountMenuUsesFallbackWhenTypedShapeDoesNotContainHeader() {
        val account =
            parseAccountInfoResponse(
                """
                {
                  "relocatedAccount": {
                    "activeAccountHeaderRenderer": {
                      "accountName": {"runs": [{"text": "Relocated account"}]},
                      "channelHandle": {"runs": [{"text": "@relocated"}]},
                      "accountPhoto": {"thumbnails": [{"url": "avatar"}]}
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals("Relocated account", account?.name)
        assertEquals("@relocated", account?.channelHandle)
    }

    @Test
    fun accountsListMergesTypedAndRelocatedShapes() {
        val accounts =
            parseAccountsListResponse(
                """
                {
                  "actions": [
                    {
                      "updateChannelSwitcherPageAction": {
                        "page": {"channelSwitcherPageRenderer": {"contents": [{
                          "accountItemRenderer": {
                            "accountName": {"runs": [{"text": "Typed channel"}]},
                            "channelHandle": {"runs": [{"text": "@typed"}]}
                          }
                        }]}}
                      }
                    },
                    {
                      "newChannelSwitcherAction": {
                        "contents": [{
                          "accountItemRenderer": {
                            "accountName": {"runs": [{"text": "Relocated channel"}]},
                            "channelHandle": {"runs": [{"text": "@relocated"}]}
                          }
                        }]
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("Typed channel", "Relocated channel"), accounts.map { it.name })
        assertEquals(listOf("@typed", "@relocated"), accounts.map { it.channelHandle })
    }
}
