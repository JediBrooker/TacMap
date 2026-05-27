package com.tacticalmaps.waypoints

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * A user-placed point of interest, stored in WGS84 so it survives swapping basemaps
 * (OSM, satellite tiles, GeoPDF, calibrated PDF).
 */
@Serializable
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("elevation_m") val elevationMetres: Double? = null,
    val kind: WaypointKind = WaypointKind.Generic,
    val rotation: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    @SerialName("layer_id") val layerId: String = DEFAULT_LAYER_ID,
    @SerialName("created_at_epoch_ms") val createdAt: Long = System.currentTimeMillis()
) {
    val elevationLabel: String? get() = elevationMetres?.let { "%.0f m".format(it) }

    companion object {
        const val DEFAULT_LAYER_ID = "default"
    }
}

@Serializable(with = WaypointKindSerializer::class)
sealed interface WaypointKind {
    val displayName: String
    val categoryDisplayName: String

    @Serializable
    data object Generic : WaypointKind {
        override val displayName: String = "Waypoint"
        override val categoryDisplayName: String = "Field Marker"
    }

    @Serializable
    data class Military(val spec: MilitarySymbolSpec = MilitarySymbolSpec()) : WaypointKind {
        override val displayName: String
            get() = buildString {
                append(spec.affiliation.displayName)
                append(' ')
                if (spec.function != SymbolFunction.UNSPECIFIED) {
                    append(spec.function.displayName)
                    append(' ')
                }
                append(spec.echelon.displayName)
            }
        override val categoryDisplayName: String = "Military Unit (APP-6C)"
    }

    @Serializable
    data class ControlMeasure(
        val measure: TacticalControlMeasure = TacticalControlMeasure.ASSEMBLY_AREA
    ) : WaypointKind {
        override val displayName: String get() = measure.displayName
        override val categoryDisplayName: String = "Tactical Task"
    }
}

object WaypointKindSerializer : KSerializer<WaypointKind> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("WaypointKind")

    override fun serialize(encoder: Encoder, value: WaypointKind) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("WaypointKind can only be encoded as JSON")
        val json = jsonEncoder.json
        val element = buildJsonObject {
            when (value) {
                WaypointKind.Generic -> put("type", "generic")
                is WaypointKind.Military -> {
                    put("type", "military")
                    put("spec", json.encodeToJsonElement(value.spec))
                }
                is WaypointKind.ControlMeasure -> {
                    put("type", "controlMeasure")
                    put("control", json.encodeToJsonElement(value.measure))
                }
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): WaypointKind {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("WaypointKind can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val json = jsonDecoder.json

        if (element is JsonPrimitive) {
            return legacyKind(element.contentOrNull)
        }

        val obj = element.jsonObject
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "military" -> WaypointKind.Military(
                obj["spec"]?.let { json.decodeFromJsonElement<MilitarySymbolSpec>(it) }
                    ?: MilitarySymbolSpec()
            )
            "controlMeasure" -> WaypointKind.ControlMeasure(
                obj["control"]?.let { json.decodeFromJsonElement<TacticalControlMeasure>(it) }
                    ?: TacticalControlMeasure.ASSEMBLY_AREA
            )
            else -> WaypointKind.Generic
        }
    }

    private fun legacyKind(raw: String?): WaypointKind = when (raw) {
        "drop_zone" -> WaypointKind.ControlMeasure(TacticalControlMeasure.LANDING_ZONE)
        "observation" -> WaypointKind.ControlMeasure(TacticalControlMeasure.OBSERVATION_POST_RECON)
        else -> WaypointKind.Generic
    }
}

@Serializable
data class MilitarySymbolSpec(
    val affiliation: SymbolAffiliation = SymbolAffiliation.FRIEND,
    val echelon: SymbolEchelon = SymbolEchelon.PLATOON,
    val function: SymbolFunction = SymbolFunction.INFANTRY,
    val isHeadquarters: Boolean = false
)

@Serializable
enum class SymbolAffiliation(val displayName: String, val fillColor: Int) {
    @SerialName("friend") FRIEND("Friendly", 0xFF80E0FF.toInt()),
    @SerialName("hostile") HOSTILE("Hostile", 0xFFFF8080.toInt()),
    @SerialName("neutral") NEUTRAL("Neutral", 0xFFAAFFAA.toInt()),
    @SerialName("unknown") UNKNOWN("Unknown", 0xFFFFFF80.toInt())
}

@Serializable
enum class SymbolEchelon(val displayName: String, val glyph: String) {
    @SerialName("team") TEAM("Team / Crew", "Ø"),
    @SerialName("section") SECTION("Section", "●"),
    @SerialName("platoon") PLATOON("Platoon", "●●●"),
    @SerialName("company") COMPANY("Company", "I"),
    @SerialName("battalionRegiment") BATTALION_REGIMENT("Battalion / Regiment", "II"),
    @SerialName("brigade") BRIGADE("Brigade", "X"),
    @SerialName("division") DIVISION("Division", "XX")
}

@Serializable
enum class SymbolFunction(val assetName: String, val displayName: String) {
    @SerialName("airDefence") AIR_DEFENCE("airDefence", "Air Defence"),
    @SerialName("ammunition") AMMUNITION("ammunition", "Ammunition"),
    @SerialName("antiTank") ANTI_TANK("antiTank", "Anti-Tank"),
    @SerialName("armour") ARMOUR("armour", "Armour"),
    @SerialName("artillery") ARTILLERY("artillery", "Artillery"),
    @SerialName("aviationFixed") AVIATION_FIXED("aviationFixed", "Aviation (Fixed-Wing)"),
    @SerialName("aviation") AVIATION("aviation", "Aviation (Rotary)"),
    @SerialName("bridging") BRIDGING("bridging", "Bridging"),
    @SerialName("cavalry") CAVALRY("cavalry", "Cavalry"),
    @SerialName("cbrn") CBRN("cbrn", "CBRN Defence"),
    @SerialName("css") CSS("css", "Combat Service Support"),
    @SerialName("electronicWarfare") ELECTRONIC_WARFARE("electronicWarfare", "Electronic Warfare"),
    @SerialName("engineer") ENGINEER("engineer", "Engineer"),
    @SerialName("eod") EOD("eod", "Explosive Ordnance Disposal"),
    @SerialName("infantry") INFANTRY("infantry", "Infantry"),
    @SerialName("maintenance") MAINTENANCE("maintenance", "Maintenance"),
    @SerialName("mechInfantry") MECH_INFANTRY("mechInfantry", "Mechanised Infantry"),
    @SerialName("medical") MEDICAL("medical", "Medical"),
    @SerialName("militaryPolice") MILITARY_POLICE("militaryPolice", "Military Police"),
    @SerialName("mortar") MORTAR("mortar", "Mortar"),
    @SerialName("motorisedInfantry") MOTORISED_INFANTRY("motorisedInfantry", "Motorised Infantry"),
    @SerialName("radar") RADAR("radar", "Radar"),
    @SerialName("recce") RECCE("recce", "Reconnaissance"),
    @SerialName("signal") SIGNAL("signal", "Signals"),
    @SerialName("specialForces") SPECIAL_FORCES("specialForces", "Special Forces"),
    @SerialName("logistics") LOGISTICS("logistics", "Supply"),
    @SerialName("transportation") TRANSPORTATION("transportation", "Transportation"),
    @SerialName("uav") UAV("uav", "Unmanned Air Vehicle"),
    @SerialName("unspecified") UNSPECIFIED("unspecified", "No Branch");

    companion object {
        val pickerEntries: List<SymbolFunction>
            get() = entries
                .filterNot { it == UNSPECIFIED }
                .sortedBy { it.displayName } + UNSPECIFIED
    }
}

@Serializable
enum class TacticalControlMeasure(val assetName: String, val displayName: String) {
    @SerialName("block") BLOCK("block", "Block"),
    @SerialName("breach") BREACH("breach", "Breach"),
    @SerialName("bypass") BYPASS("bypass", "Bypass"),
    @SerialName("canalise") CANALISE("canalise", "Canalise"),
    @SerialName("clear") CLEAR("clear", "Clear"),
    @SerialName("contain") CONTAIN("contain", "Contain"),
    @SerialName("counterattack") COUNTERATTACK("counterattack", "Counter-Attack"),
    @SerialName("counterattackByFire") COUNTERATTACK_BY_FIRE("counterattackByFire", "Counter-Attack by Fire"),
    @SerialName("delay") DELAY("delay", "Delay"),
    @SerialName("destroy") DESTROY("destroy", "Destroy"),
    @SerialName("disrupt") DISRUPT("disrupt", "Disrupt"),
    @SerialName("fix") FIX("fix", "Fix"),
    @SerialName("interdict") INTERDICT("interdict", "Interdict"),
    @SerialName("isolate") ISOLATE("isolate", "Isolate"),
    @SerialName("neutralise") NEUTRALISE("neutralise", "Neutralise"),
    @SerialName("occupy") OCCUPY("occupy", "Occupy"),
    @SerialName("penetrate") PENETRATE("penetrate", "Penetrate"),
    @SerialName("reliefInPlace") RELIEF_IN_PLACE("reliefInPlace", "Relief in Place"),
    @SerialName("retain") RETAIN("retain", "Retain"),
    @SerialName("secure") SECURE("secure", "Secure"),
    @SerialName("screen") SCREEN("screen", "Screen"),
    @SerialName("guard") GUARD("guard", "Guard"),
    @SerialName("cover") COVER("cover", "Cover"),
    @SerialName("seize") SEIZE("seize", "Seize"),
    @SerialName("withdraw") WITHDRAW("withdraw", "Withdraw"),
    @SerialName("withdrawUnderPressure") WITHDRAW_UNDER_PRESSURE("withdrawUnderPressure", "Withdraw Under Pressure"),
    @SerialName("landingZone") LANDING_ZONE("landingZone", "Landing Zone"),
    @SerialName("ccp") CCP("ccp", "Casualty Collection Point"),
    @SerialName("observationPostRecon") OBSERVATION_POST_RECON("observationPostRecon", "Observation Post (Recon)"),
    @SerialName("axisOfMainAttack") AXIS_OF_MAIN_ATTACK("axisOfMainAttack", "Axis of Main Attack"),
    @SerialName("axisOfSupportingAttack") AXIS_OF_SUPPORTING_ATTACK("axisOfSupportingAttack", "Axis of Supporting Attack"),
    @SerialName("attackByFire") ATTACK_BY_FIRE("attackByFire", "Attack by Fire"),
    @SerialName("supportByFire") SUPPORT_BY_FIRE("supportByFire", "Support by Fire"),
    @SerialName("ambush") AMBUSH("ambush", "Ambush"),
    @SerialName("antipersonnelMinefield") ANTIPERSONNEL_MINEFIELD("antipersonnelMinefield", "Anti-Personnel Minefield"),
    @SerialName("turn") TURN("turn", "Turn"),
    @SerialName("assemblyArea") ASSEMBLY_AREA("assemblyArea", "Assembly Area"),
    @SerialName("formUpPoint") FORM_UP_POINT("formUpPoint", "Form-Up Point");

    companion object {
        val pickerEntries: List<TacticalControlMeasure>
            get() = entries.sortedBy { it.displayName }
    }
}
