package li.cil.oc.common.event

import li.cil.oc.Settings
import li.cil.oc.api.event.GeolyzerEvent
import li.cil.oc.util.BlockPosition
import li.cil.oc.util.ExtendedWorld._
import net.minecraft.block.Block
import net.minecraft.init.Blocks
import net.minecraft.util.BlockPos
import net.minecraftforge.fluids.FluidRegistry
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

import scala.collection.convert.WrapAsScala._

object GeolyzerHandler {
  @SubscribeEvent
  def onGeolyzerScan(e: GeolyzerEvent.Scan) {
    val world = e.host.world
    val blockPos = BlockPosition(e.host)
    val bx = blockPos.x + e.scanX
    val bz = blockPos.z + e.scanZ
    val includeReplaceable = e.options.get("includeReplaceable") match {
      case value: java.lang.Boolean => value.booleanValue()
      case _ => true
    }

    val noise = new Array[Byte](e.data.length)
    world.rand.nextBytes(noise)
    // Map to [-1, 1). The additional /33f is for normalization below.
    noise.map(_ / 128f / 33f).copyToArray(e.data)

    for (ry <- 0 until e.data.length) {
      val by = blockPos.y + ry - 32
      val bp = new BlockPos(bx, by, bz)
      if (!world.isAirBlock(bp)) {
        val state = world.getBlockState(bp)
        val block = state.getBlock
        if (!block.isAir(world, bp) && (includeReplaceable || !block.isReplaceable(world, bp) || isFluid(block))) {
          e.data(ry) = e.data(ry) * (math.abs(ry - 32) + 1) * Settings.get.geolyzerNoise + block.getBlockHardness(world, bp)
        }
        else e.data(ry) = 0
      }
      else e.data(ry) = 0
    }
  }

  @SubscribeEvent
  def onGeolyzerAnalyze(e: GeolyzerEvent.Analyze) {
    val world = e.host.world
    val blockPos = BlockPosition(e.host).offset(e.side).toBlockPos
    val state = world.getBlockState(blockPos)
    val block = state.getBlock
    val metadata = block.getMetaFromState(state)

    e.data += "name" -> Block.blockRegistry.getNameForObject(block)
    e.data += "metadata" -> int2Integer(metadata)
    e.data += "hardness" -> float2Float(block.getBlockHardness(world, blockPos))
    e.data += "harvestLevel" -> int2Integer(block.getHarvestLevel(state))
    e.data += "harvestTool" -> block.getHarvestTool(state)
    e.data += "color" -> int2Integer(block.getMapColor(state).colorValue)

    if (Settings.get.insertIdsInConverters)
      e.data += "id" -> int2Integer(Block.getIdFromBlock(block))
  }

  private def isFluid(block: Block) = FluidRegistry.lookupFluidForBlock(block) != null
}
