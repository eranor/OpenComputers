package li.cil.oc.client.gui.widget

import li.cil.oc.client.Textures
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Tessellator

class ProgressBar(val x: Int, val y: Int) extends Widget {
  override def width = 140

  override def height = 12

  var level = 0.0

  def draw() {
    if (level > 0) {
      val u0 = 0
      val u1 = width / 256.0 * level
      val v0 = 1 - height / 256.0
      val v1 = 1
      val tx = owner.windowX + x
      val ty = owner.windowY + y
      val w = width * level

      Minecraft.getMinecraft.renderEngine.bindTexture(Textures.GUI.Bar)
      val t = Tessellator.getInstance
      val r = t.getWorldRenderer
      r.startDrawingQuads()
      r.addVertexWithUV(tx, ty, owner.windowZ, u0, v0)
      r.addVertexWithUV(tx, ty + height, owner.windowZ, u0, v1)
      r.addVertexWithUV(tx + w, ty + height, owner.windowZ, u1, v1)
      r.addVertexWithUV(tx + w, ty, owner.windowZ, u1, v0)
      t.draw()
    }
  }
}
