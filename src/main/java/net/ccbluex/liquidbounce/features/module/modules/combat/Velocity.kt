/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

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
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.PacketUtils
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.util.EnumFacing
import kotlin.math.atan2
import kotlin.math.sqrt

object Velocity : Module("Velocity", ModuleCategory.COMBAT) {
    /**
     * OPTIONS
     */
    private val mode by ListValue(
        "Mode", arrayOf(
            "Simple", "AAC", "AACPush", "AACZero", "AACv4", "GrimS32",
            "GrimLatest", "Reverse", "SmoothReverse", "Jump", "Glitch", "Legit"
        ), "Simple"
    )

    private val horizontal by FloatValue("Horizontal", 0F, 0F..1F) { mode in arrayOf("Simple", "AAC", "Legit") }
    private val vertical by FloatValue("Vertical", 0F, 0F..1F) { mode in arrayOf("Simple", "Legit") }

    // Reverse
    private val reverseStrength by FloatValue("ReverseStrength", 1F, 0.1F..1F) { mode == "Reverse" }
    private val reverse2Strength by FloatValue("SmoothReverseStrength", 0.05F, 0.02F..0.1F) { mode == "SmoothReverse" }

    // AAC Push
    private val aacPushXZReducer by FloatValue("AACPushXZReducer", 2F, 1F..3F) { mode == "AACPush" }
    private val aacPushYReducer by BoolValue("AACPushYReducer", true) { mode == "AACPush" }

    // AAC v4
    private val aacv4MotionReducer by FloatValue("AACv4MotionReducer", 0.62F, 0F..1F) { mode == "AACv4" }

    // Legit
    private val legitDisableInAir by BoolValue("DisableInAir", true) { mode == "Legit" }

    // Chance
    private val chance by IntegerValue("Chance", 100, 0..100) { mode == "Jump" || mode == "Legit" }

    // Jump
    private val jumpCooldownMode by ListValue("JumpCooldownMode", arrayOf("Ticks", "ReceivedHits"), "Ticks")
        { mode == "Jump" }
    private val ticksUntilJump by IntegerValue("TicksUntilJump", 4, 0..20)
        { jumpCooldownMode == "Ticks" && mode == "Jump" }
    private val hitsUntilJump by IntegerValue("ReceivedHitsUntilJump", 2, 0..5)
        { jumpCooldownMode == "ReceivedHits" && mode == "Jump" }


    // GrimS32
    private val cancelPacket by IntegerValue("CancelPacket", 6, 0..20) { mode == "GrimS32" }
    private val resetPersec by IntegerValue("ResetPerMin", 10, 0..30) { mode == "GrimS32" }

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

    // GrimLatest
    private val onVelocity by ListValue("OnVelocity", arrayOf("Always", "CombatManager", "PacketDamage"), defaultValue = "Always") { mode == "GrimLatest" }

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

    // SmoothReverse
    private var reverseHurt = false
    
    // GrimS32
    private var grimTCancel = 0
    private var updates = 0

    // GrimLatest
    private var canSpoof = false
    private var canCancel = false

    // AACPush
    private var jump = false

    // Jump
    private var limitUntilJump = 0

    override val tag
        get() = mode

    override fun onDisable() {
        mc.thePlayer?.speedInAir = 0.02F
    }

    override fun onEnable() {
        canSpoof = false
        canCancel = false
        grimTCancel = 0
    }

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        val thePlayer = mc.thePlayer ?: return

        if (thePlayer.isInWater || thePlayer.isInLava || thePlayer.isInWeb)
            return

        when (mode.lowercase()) {
            "glitch" -> {
                thePlayer.noClip = hasReceivedVelocity

                if (thePlayer.hurtTime == 7)
                    thePlayer.motionY = 0.4

                hasReceivedVelocity = false
            }

            "reverse" -> {
                if (!hasReceivedVelocity)
                    return

                if (!thePlayer.onGround) {
                    speed *= reverseStrength
                } else if (velocityTimer.hasTimePassed(80))
                    hasReceivedVelocity = false
            }

            "smoothreverse" -> {
                if (!hasReceivedVelocity) {
                    thePlayer.speedInAir = 0.02F
                    return
                }

                if (thePlayer.hurtTime > 0)
                    reverseHurt = true

                if (!thePlayer.onGround) {
                    if (reverseHurt)
                        thePlayer.speedInAir = reverse2Strength
                } else if (velocityTimer.hasTimePassed(80)) {
                    hasReceivedVelocity = false
                    reverseHurt = false
                }
            }

            "aac" -> if (hasReceivedVelocity && velocityTimer.hasTimePassed(80)) {
                thePlayer.motionX *= horizontal
                thePlayer.motionZ *= horizontal
                //mc.thePlayer.motionY *= vertical ?
                hasReceivedVelocity = false
            }

            "aacv4" ->
                if (thePlayer.hurtTime > 0 && !thePlayer.onGround) {
                    val reduce = aacv4MotionReducer
                    thePlayer.motionX *= reduce
                    thePlayer.motionZ *= reduce
                }

            "aacpush" -> {
                if (jump) {
                    if (thePlayer.onGround)
                        jump = false
                } else {
                    // Strafe
                    if (thePlayer.hurtTime > 0 && thePlayer.motionX != 0.0 && thePlayer.motionZ != 0.0)
                        thePlayer.onGround = true

                    // Reduce Y
                    if (thePlayer.hurtResistantTime > 0 && aacPushYReducer && !Speed.handleEvents())
                        thePlayer.motionY -= 0.014999993
                }

                // Reduce XZ
                if (thePlayer.hurtResistantTime >= 19) {
                    val reduce = aacPushXZReducer

                    thePlayer.motionX /= reduce
                    thePlayer.motionZ /= reduce
                }
            }

            "grims32" -> {
                updates++

                if (resetPersec > 0 && (updates >= 0 || updates >= resetPersec)) {
                    updates = 0
                    if (grimTCancel > 0) grimTCancel--
                }
            }

            "grimlatest" -> {
                if (onVelocity.get().equals("Always", true) || (onVelocity.get().equals("CombatManager", true) && LiquidBounce.combatManager.inCombat)) {
                    canCancel = true
                }
                if (canSpoof) {
                    val pos = mc.thePlayer.getPosition()
                    PacketUtils.sendPacket(C06PacketPlayerPosLook(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround), false)
                    PacketUtils.sendPacket(C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN), false)
                    canSpoof = false
                }
            }

            "aaczero" ->
                if (thePlayer.hurtTime > 0) {
                    if (!hasReceivedVelocity || thePlayer.onGround || thePlayer.fallDistance > 2F)
                        return

                    thePlayer.motionY -= 1.0
                    thePlayer.isAirBorne = true
                    thePlayer.onGround = true
                } else
                    hasReceivedVelocity = false

            "legit" -> {
                if (legitDisableInAir && !isOnGround(0.5))
                    return

                if (mc.thePlayer.maxHurtResistantTime != mc.thePlayer.hurtResistantTime || mc.thePlayer.maxHurtResistantTime == 0)
                    return

                if (nextInt(endExclusive = 100) < chance) {
                    val horizontal = horizontal / 100f
                    val vertical = vertical / 100f

                    thePlayer.motionX *= horizontal.toDouble()
                    thePlayer.motionZ *= horizontal.toDouble()
                    thePlayer.motionY *= vertical.toDouble()
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

                "aac", "reverse", "smoothreverse", "aaczero" -> hasReceivedVelocity = true

                "jump" -> {
                    var packetDirection: Double = 0.0
                    when (packet) {
                        is S12PacketEntityVelocity -> {
                            val motionX: Double = packet.motionX.toDouble()
                            val motionZ: Double = packet.motionZ.toDouble()

                            packetDirection = atan2(motionX, motionZ)
                        }
                        is S27PacketExplosion -> {
                            val motionX: Double = packet.field_149152_f.toDouble()
                            val motionZ: Double = packet.field_149159_h.toDouble()

                            packetDirection = atan2(motionX, motionZ)
                        }
                    }
                    val degreePlayer = getDirection()
                    val degreePacket = Math.floorMod(packetDirection.toDegrees().toInt(), 360).toDouble()
                    var angle = Math.abs(degreePacket + degreePlayer)
                    val threshold = 90.0
                    angle = Math.floorMod(angle.toInt(), 360).toDouble()
                    val inRange = angle in 180-threshold/2..180+threshold/2
                    if (inRange)
                        hasReceivedVelocity = true
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

                "grimlatest" -> {
                    val packet = event.packet
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

                "glitch" -> {
                    if (!thePlayer.onGround)
                        return

                    hasReceivedVelocity = true
                    event.cancelEvent()
                }
            }
        }
    }

    @EventTarget
    fun onJump(event: JumpEvent) {
        val thePlayer = mc.thePlayer

        if (thePlayer == null || thePlayer.isInWater || thePlayer.isInLava || thePlayer.isInWeb)
            return

        when (mode.lowercase()) {
            "aacpush" -> {
                jump = true

                if (!thePlayer.isCollidedVertically)
                    event.cancelEvent()
            }

            "aaczero" ->
                if (thePlayer.hurtTime > 0)
                    event.cancelEvent()
        }
    }

    @EventTarget
    fun onStrafe(event: StrafeEvent) {
        val player = mc.thePlayer ?: return

        if (mode == "Jump" && hasReceivedVelocity) {
            if (nextInt(endExclusive = 100) < chance && shouldJump() && player.isSprinting && player.onGround && player.hurtTime == 9) {
                player.jump()
                limitUntilJump = 0
            }
            hasReceivedVelocity = false
            return
        }

        when (jumpCooldownMode.lowercase()) {
            "ticks" -> limitUntilJump++
            "receivedhits" -> if (player.hurtTime == 9) limitUntilJump++
        }
    }

    private fun shouldJump() = when (jumpCooldownMode.lowercase()) {
        "ticks" -> limitUntilJump >= ticksUntilJump
        "receivedhits" -> limitUntilJump >= hitsUntilJump
        else -> false
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
