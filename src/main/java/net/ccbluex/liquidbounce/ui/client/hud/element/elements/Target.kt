/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */

package net.ccbluex.liquidbounce.ui.client.hud.element.elements

import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.ui.client.hud.element.Border
import net.ccbluex.liquidbounce.ui.client.hud.element.Element
import net.ccbluex.liquidbounce.ui.client.hud.element.ElementInfo
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.EntityUtils.getHealth
import net.ccbluex.liquidbounce.utils.extensions.getDistanceToEntityBox
import net.ccbluex.liquidbounce.utils.render.RenderUtils.deltaTime
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawBorderedRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawRect
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawScaledCustomSizeModalRect
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.EntityLivingBase
import net.ccbluex.liquidbounce.value.BoolValue
import net.ccbluex.liquidbounce.value.FloatValue
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.glColor4f
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.abs
import kotlin.math.pow

/**
 * Raven B4 target hud from MinusBounce
 */
@ElementInfo(name = "TargetHud")
class Target : Element() {
    override fun drawTarget(entity: EntityPlayer) {
        val font = Fonts.minecraftFont
        val hp = decimalFormat2.format(entity.health)
        val hplength = font.getStringWidth(decimalFormat2.format(entity.health))
        val length = font.getStringWidth(entity.displayName.formattedText)
        GlStateManager.pushMatrix()
        updateAnim(entity.health)
        RenderUtils.drawRoundedGradientOutlineCorner(
            0F,
            0F,
            length + hplength + 23F,
            35F,
            2F, 8F,
            targetInstance.barColor.rgb,
            targetInstance.barColor.rgb
        )
        RenderUtils.drawRoundedRect(0F, 0F, length + hplength + 23F, 35F, 4F, Color(0, 0, 0, 100).rgb)
        GlStateManager.enableBlend()
        font.drawStringWithShadow(
            entity.displayName.formattedText,
            6F,
            8F,
            Color(255, 255, 255, 255).rgb
        )
        val winorlose = if(entity.health < mc.thePlayer.health) "W" else "L"
        font.drawStringWithShadow(
            winorlose,
            length + hplength + 11.6F,
            8F,  (if (winorlose == "W")  Color(0, 255, 0).rgb else Color(139, 0, 0).rgb))
        font.drawStringWithShadow(
            hp,
            length + 8F,
            8F,
            ColorUtils.reAlpha(BlendUtils.getHealthColor(entity.health, entity.maxHealth), 255).rgb
        )
        GlStateManager.disableAlpha()
        GlStateManager.disableBlend()
        RenderUtils.drawRoundedRect(
            5.0F,
            29.55F,
            length + hplength + 18F,
            25F,
            2F,
            Color(0, 0, 0, 110).rgb,
        )
        RenderUtils.drawRoundedGradientRectCorner(
            5F,
            25F,
            8F + (entity.health / 20) * (length + hplength + 10F),
            29.5F,
            4F,
            targetInstance.barColor.rgb,
            targetInstance.barColor.rgb
        )
        GlStateManager.popMatrix()

    }

    override fun getBorder(entity: EntityPlayer?): Border? {
        return Border(0F, 0F, 40F + mc.fontRendererObj.getStringWidth(entity!!.displayName.formattedText) ,35F)
    }
}
