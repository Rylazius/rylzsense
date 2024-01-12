/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.movement.Speed
import net.ccbluex.liquidbounce.utils.*
import net.ccbluex.liquidbounce.utils.MovementUtils.isOnGround
import net.ccbluex.liquidbounce.utils.MovementUtils.speed
import net.ccbluex.liquidbounce.utils.extensions.toDegrees
import net.ccbluex.liquidbounce.utils.misc.RandomUtils.nextInt
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.ccbluex.liquidbounce.value.BoolValue
import net.ccbluex.liquidbounce.value.FloatValue
import net.ccbluex.liquidbounce.value.IntegerValue
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.network.play.server.*
import net.minecraft.network.play.client.*
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import net.minecraft.util.*
import kotlin.math.atan2
import kotlin.math.sqrt


object Velocity2 : Module("Velocity2", ModuleCategory.COMBAT) {

    /**
     * OPTIONS
     */
    private val mode by ListValue(
        "Mode", arrayOf(
            "Simple", "GrimLastest", "GrimS32"
        ), "GrimLastest"
    )

    private val horizontal by FloatValue("Horizontal", 0F, 0F..1F) { mode in arrayOf("Simple") }
    private val vertical by FloatValue("Vertical", 0F, 0F..1F) { mode in arrayOf("Simple") }

    // GrimS32
    private val cancelPacket by IntegerValue("CancelPacket", 6, 0..20) { mode == "GrimS32" }
    private val resetPersec by IntegerValue("ResetPerMin", 10, 0..30) { mode == "GrimS32" }

    // GrimLastest from TuiLaZeroTwo
    private var onVelocity = ListValue("OnVelocity", arrayOf("Always", "CombatManager", "PacketDamage"), "Always")

    private var maxMotionRangeValue: IntegerValue = object: IntegerValue("MaxMotionRange", -500, -1000..1000) {
        override fun onChanged(oldValue: Int, newValue: Int) {
            val v = minMotionRangeValue.get()
            if (v > newValue) set(v)
        }
    }

    private var minMotionRangeValue: IntegerValue = object: IntegerValue("MinMotionRange", -500, -1000..1000) {
        override fun onChanged(oldValue: Int, newValue: Int) {
            val v = maxMotionRangeValue.get()
            if (v < newValue) set(v)
        }
    }

    // TODO: Could this be useful in other modes? (Jump?)
    // Limits
    private val limitMaxMotionValue = BoolValue("LimitMaxMotion", false) { mode == "Simple" }
    private val maxXZMotion by FloatValue("MaxXZMotion", 0.4f, 0f..1.9f) { limitMaxMotionValue.isActive() }
    private val maxYMotion by FloatValue("MaxYMotion", 0.36f, 0f..0.46f) { limitMaxMotionValue.isActive() } //0.00075 is added silently

    // Vanilla XZ limits
    // Non-KB: 0.4 (no sprint), 0.9 (sprint)
    // KB 1: 0.9 (no sprint), 1.4 (sprint)
    // KB 2: 1.4 (no sprint), 1.9 (sprint)
    // Vanilla Y limits
    // 0.36075 (no sprint), 0.46075 (sprint)

    /**
     * VALUES
     */
    private val velocityTimer = MSTimer()
    private var hasReceivedVelocity = false

    // GrimS32
    private var grimTCancel = 0
    private var updates = 0
    
    override val tag
        get() = mode

    override fun onDisable() {
        mc.thePlayer?.speedInAir = 0.02F
    }

    override fun onEnable() {
        grimTCancel = 0
        canSpoof = false
        canCancel = false
    }
    // GrimLastest
    private var canSpoof = false
    private var canCancel = false

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        val thePlayer = mc.thePlayer ?: return

        if (thePlayer.isInWater || thePlayer.isInLava || thePlayer.isInWeb)
            return

        when (mode.lowercase()) {

            "grimlastest" -> {
             if (onVelocity.get().equals("Always", true) || (onVelocity.get().equals("CombatManager", true) && LiquidBounce.combatManager.inCombat)) {
             canCancel = true
            }

            if (canSpoof) {
                val pos = mc.thePlayer.getPosition()
                PacketUtils.sendPacketNoEvent(C06PacketPlayerPosLook(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround))
                PacketUtils.sendPacketNoEvent(C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN))
                canSpoof = false
                }
            }

            "grims32" -> {
                updates++

                if (resetPersec > 0 && (updates >= 0 || updates >= resetPersec)) {
                    updates = 0
                    if (grimTCancel > 0) grimTCancel--
                }
            }
        }
    }

    fun getDirection(): Double {
        var moveYaw = mc.thePlayer.rotationYaw
        if (mc.thePlayer.moveForward != 0f && mc.thePlayer.moveStrafing == 0f) {
            moveYaw += if (mc.thePlayer.moveForward > 0) 0 else 180
        } else if (mc.thePlayer.moveForward != 0f && mc.thePlayer.moveStrafing != 0f) {
            if (mc.thePlayer.moveForward > 0) moveYaw += if (mc.thePlayer.moveStrafing > 0) -45 else 45 else moveYaw -= if (mc.thePlayer.moveStrafing > 0) -45 else 45
            moveYaw += if (mc.thePlayer.moveForward > 0) 0 else 180
        } else if (mc.thePlayer.moveStrafing != 0f && mc.thePlayer.moveForward == 0f) {
            moveYaw += if (mc.thePlayer.moveStrafing > 0) -90 else 90
        }
        return Math.floorMod(moveYaw.toInt(), 360).toDouble()
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val thePlayer = mc.thePlayer ?: return

        val packet = event.packet

        if (
            (
                packet is S12PacketEntityVelocity
                    && thePlayer.entityId == packet.entityID
                    && packet.motionY > 0
                    && (packet.motionX != 0 || packet.motionZ != 0)
            ) || (
                packet is S27PacketExplosion
                    && packet.field_149153_g > 0f
                    && (packet.field_149152_f != 0f || packet.field_149159_h != 0f)
            )
        ) {
            velocityTimer.reset()

            when (mode.lowercase()) {
                "simple" -> handleVelocity(event)

                "grimlastest" -> {
                        if (packet is S19PacketEntityStatus && onVelocity.get().equals("PacketDamage", true)) {
                        val player = packet.getEntity(mc.theWorld)
                        if (player != mc.thePlayer || packet.opCode != 2.toByte()) 
                            return
                        canCancel = true
                    }

                    if (packet is S12PacketEntityVelocity && packet.entityID == mc.thePlayer.entityId && canCancel) {
                        event.cancelEvent()
                        canCancel = false
                        canSpoof = true
                    }
                }

                "grims32" -> {
                    val packet = event.packet
                    if (packet is S12PacketEntityVelocity && packet.entityID == mc.thePlayer.entityId) {
                        if (packet.motionX < minMotionRangeValue.get() || packet.motionX > maxMotionRangeValue.get() || packet.motionZ < minMotionRangeValue.get() || packet.motionZ > maxMotionRangeValue.get()) {
                            event.cancelEvent()
                            grimTCancel = cancelPacket
                        }
                    }
                    if (packet is S32PacketConfirmTransaction && grimTCancel > 0) {
                        event.cancelEvent()
                        grimTCancel--
                    }
                }

            }
        }
    }

    private fun handleVelocity(event: PacketEvent) {
        val packet = event.packet

        if (packet is S12PacketEntityVelocity) {
            // Always cancel event and handle motion from here
            event.cancelEvent()

            if (horizontal == 0f && vertical == 0f)
                return

            // Don't modify player's motionXZ when horizontal value is 0
            if (horizontal != 0f) {
                var motionX = packet.realMotionX
                var motionZ = packet.realMotionZ

                if (limitMaxMotionValue.get()) {
                    val distXZ = sqrt(motionX * motionX + motionZ * motionZ)

                    if (distXZ > maxXZMotion) {
                        val ratioXZ = maxXZMotion / distXZ

                        motionX *= ratioXZ
                        motionZ *= ratioXZ
                    }
                }

                mc.thePlayer.motionX = motionX
                mc.thePlayer.motionZ = motionZ
            }

            // Don't modify player's motionY when vertical value is 0
            if (vertical != 0f) {
                var motionY = packet.realMotionY

                if (limitMaxMotionValue.get())
                    motionY = motionY.coerceAtMost(maxYMotion + 0.00075)

                mc.thePlayer.motionY = motionY
            }
        } else if (packet is S27PacketExplosion) {
            // Don't cancel explosions, modify them, they could change blocks in the world
            if (horizontal != 0f && vertical != 0f) {
                packet.field_149152_f = 0f
                packet.field_149153_g = 0f
                packet.field_149159_h = 0f

                return
            }

            // Unlike with S12PacketEntityVelocity explosion packet motions get added to player motion, doesn't replace it
            // Velocity might behave a bit differently, especially LimitMaxMotion
            packet.field_149152_f *= horizontal // motionX
            packet.field_149153_g *= vertical // motionY
            packet.field_149159_h *= horizontal // motionZ

            if (limitMaxMotionValue.get()) {
                val distXZ = sqrt(packet.field_149152_f * packet.field_149152_f + packet.field_149159_h * packet.field_149159_h)
                val distY = packet.field_149153_g
                val maxYMotion = maxYMotion + 0.00075f

                if (distXZ > maxXZMotion) {
                    val ratioXZ = maxXZMotion / distXZ

                    packet.field_149152_f *= ratioXZ
                    packet.field_149159_h *= ratioXZ
                }

                if (distY > maxYMotion) {
                    packet.field_149153_g *= maxYMotion / distY
                }
            }
        }
    }
}
