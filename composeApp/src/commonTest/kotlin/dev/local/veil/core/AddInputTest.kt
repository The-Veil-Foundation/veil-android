package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AddInputTest {
    private val sub = "https://panel.example.com/api/sub/AbCd123?format=v2ray"

    private fun subscription(raw: String): String =
        assertIs<AddInput.Kind.Subscription>(AddInput.classify(raw)).url

    @Test
    fun plainSubscriptionUrl() = assertEquals(sub, subscription(sub))

    @Test
    fun invisibleCharactersAreStripped() = assertEquals(sub, subscription("﻿$sub​\n"))

    @Test
    fun clientDeeplinksUnwrapToSubscription() {
        assertEquals(sub, subscription("happ://add/$sub"))
        assertEquals(sub, subscription("v2raytun://import/$sub"))
        assertEquals(sub, subscription("hiddify://import/$sub#My%20VPN"))
        val encoded = "https%3A%2F%2Fpanel.example.com%2Fapi%2Fsub%2FAbCd123%3Fformat%3Dv2ray"
        assertEquals(sub, subscription("clash://install-config?url=$encoded&name=x"))
        assertEquals(sub, subscription("sing-box://import-remote-profile?url=$encoded#name"))
        assertEquals(sub, subscription("v2rayng://install-sub?url=$encoded"))
    }

    @Test
    fun vlessLinkIsServer() {
        val link = "vless://3f1c2a9e-7b4d-4e8a-9c21-5d6f7e8a9b0c@example.net:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=Zx9Qw3Er5Ty7Ui9Op1As3Df5Gh7Jk9Lz1Xc3Vb5Nm7Q&sid=6ba8&type=tcp#Node"
        assertIs<AddInput.Kind.Servers>(AddInput.classify(link))
    }
}
