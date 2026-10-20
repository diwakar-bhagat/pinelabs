package com.bohemian.odoo.pos.odoo

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SessionService(private val odoo: OdooJson2Client) {
    /** pos.session.create opens in 'opening_control'; set_opening_control moves it to 'opened'. */
    suspend fun open(configId: Int, openingCash: Int = 0): Int {
        val sessionId = odoo.call("pos.session", "create", jsonOf(
            "vals_list" to listOf(mapOf("config_id" to configId)),
        )).let { el ->
            // create returns a list of ids via JSON-2
            (el as? kotlinx.serialization.json.JsonArray)?.first()?.jsonPrimitive?.intOrNull
                ?: el.jsonPrimitive.intOrNull
                ?: error("unexpected create() result: $el")
        }
        odoo.call("pos.session", "set_opening_control", jsonOf(
            "ids" to listOf(sessionId), "cashbox_value" to openingCash, "notes" to "Opened from E700 till",
        ))
        return sessionId
    }
}
