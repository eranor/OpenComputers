package li.cil.oc.util

import li.cil.oc.util.ExtendedWorld._
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.item.EntityMinecartContainer
import net.minecraft.inventory.IInventory
import net.minecraft.inventory.ISidedInventory
import net.minecraft.item.ItemStack
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.util.EnumFacing

import scala.collection.convert.WrapAsScala._

object InventoryUtils {
  /**
   * Retrieves an actual inventory implementation for a specified world coordinate.
   * <p/>
   * This performs special handling for (double-)chests and also checks for
   * mine carts with chests.
   */
  def inventoryAt(position: BlockPosition): Option[IInventory] = position.world match {
    case Some(world) if world.blockExists(position) => world.getTileEntity(position) match {
      case chest: TileEntityChest => Option(net.minecraft.init.Blocks.chest.getLockableContainer(world, chest.getPos))
      case inventory: IInventory => Some(inventory)
      case _ => world.getEntitiesWithinAABB(classOf[EntityMinecartContainer], position.bounds).
        map(_.asInstanceOf[EntityMinecartContainer]).
        find(!_.isDead)
    }
    case _ => None
  }

  /**
   * Inserts a stack into an inventory.
   * <p/>
   * Only tries to insert into the specified slot. This <em>cannot</em> be
   * used to empty a slot. It can only insert stacks into empty slots and
   * merge additional items into an existing stack in the slot.
   * <p/>
   * The passed stack's size will be adjusted to reflect the number of items
   * inserted into the inventory, i.e. if 10 more items could fit into the
   * slot, the stack's size will be 10 smaller than before the call.
   * <p/>
   * This will return <tt>true</tt> if <em>at least</em> one item could be
   * inserted into the slot. It will return <tt>false</tt> if the passed
   * stack did not change.
   * <p/>
   * This takes care of handling special cases such as sided inventories,
   * maximum inventory and item stack sizes.
   * <p/>
   * The number of items inserted can be limited, to avoid unnecessary
   * changes to the inventory the stack may come from, for example.
   */
  def insertIntoInventorySlot(stack: ItemStack, inventory: IInventory, side: Option[EnumFacing], slot: Int, limit: Int = 64, simulate: Boolean = false) =
    (stack != null && limit > 0) && {
      val isSideValidForSlot = (inventory, side) match {
        case (inventory: ISidedInventory, Some(s)) => inventory.canInsertItem(slot, stack, s)
        case _ => true
      }
      (stack.stackSize > 0 && inventory.isItemValidForSlot(slot, stack) && isSideValidForSlot) && {
        val maxStackSize = math.min(inventory.getInventoryStackLimit, stack.getMaxStackSize)
        val existing = inventory.getStackInSlot(slot)
        val shouldMerge = existing != null && existing.stackSize < maxStackSize &&
          existing.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(existing, stack)
        if (shouldMerge) {
          val space = maxStackSize - existing.stackSize
          val amount = math.min(space, math.min(stack.stackSize, limit))
          stack.stackSize -= amount
          if (simulate) amount > 0
          else {
            existing.stackSize += amount
            inventory.markDirty()
            true
          }
        }
        else (existing == null) && {
          val amount = math.min(maxStackSize, math.min(stack.stackSize, limit))
          val inserted = stack.splitStack(amount)
          if (simulate) amount > 0
          else {
            inventory.setInventorySlotContents(slot, inserted)
            true
          }
        }
      }
    }

  /**
   * Extracts a stack from an inventory.
   * <p/>
   * Only tries to extract from the specified slot. This <em>can</em> be used
   * to empty a slot. It will extract items using the specified consumer method
   * which is called with the extracted stack before the stack in the inventory
   * that we extract from is cleared from. This allows placing back excess
   * items with as few inventory updates as possible.
   * <p/>
   * The consumer is the only way to retrieve the actually extracted stack. It
   * is called with a separate stack instance, so it does not have to be copied
   * again.
   * <p/>
   * This will return <tt>true</tt> if <em>at least</em> one item could be
   * extracted from the slot. It will return <tt>false</tt> if the stack in
   * the slot did not change.
   * <p/>
   * This takes care of handling special cases such as sided inventories and
   * maximum stack sizes.
   * <p/>
   * The number of items extracted can be limited, to avoid unnecessary
   * changes to the inventory the stack is extracted from. Note that this could
   * also be achieved by a check in the consumer, but it saves some unnecessary
   * code repetition this way.
   */
  def extractFromInventorySlot(consumer: (ItemStack) => Unit, inventory: IInventory, side: EnumFacing, slot: Int, limit: Int = 64) = {
    val stack = inventory.getStackInSlot(slot)
    (stack != null && limit > 0) && {
      val isSideValidForSlot = inventory match {
        case inventory: ISidedInventory => inventory.canExtractItem(slot, stack, side)
        case _ => true
      }
      (stack.stackSize > 0 && isSideValidForSlot) && {
        val maxStackSize = math.min(inventory.getInventoryStackLimit, stack.getMaxStackSize)
        val amount = math.min(maxStackSize, math.min(stack.stackSize, limit))
        val extracted = stack.splitStack(amount)
        consumer(extracted)
        val success = extracted.stackSize < amount
        stack.stackSize += extracted.stackSize
        if (stack.stackSize == 0) {
          inventory.setInventorySlotContents(slot, null)
        }
        else if (success) {
          inventory.markDirty()
        }
        success
      }
    }
  }

  /**
   * Inserts a stack into an inventory.
   * <p/>
   * This will try to fit the stack in any and as many as necessary slots in
   * the inventory. It will first try to merge the stack in stacks already
   * present in the inventory. After that it will try to fit the stack into
   * empty slots in the inventory.
   * <p/>
   * This uses the <tt>insertIntoInventorySlot</tt> method, and therefore
   * handles special cases such as sided inventories and stack size limits.
   * <p/>
   * This returns <tt>true</tt> if at least one item was inserted. The passed
   * item stack will be adjusted to reflect the number items inserted, by
   * having its size decremented accordingly.
   */
  def insertIntoInventory(stack: ItemStack, inventory: IInventory, side: Option[EnumFacing] = None, limit: Int = 64, simulate: Boolean = false, slots: Option[Iterable[Int]] = None) =
    (stack != null && limit > 0) && {
      var success = false
      var remaining = limit
      val range = slots.getOrElse(0 until inventory.getSizeInventory)

      val shouldTryMerge = !stack.isItemStackDamageable && stack.getMaxStackSize > 1 && inventory.getInventoryStackLimit > 1
      if (shouldTryMerge) {
        for (slot <- range) {
          val stackSize = stack.stackSize
          if ((inventory.getStackInSlot(slot) != null) && insertIntoInventorySlot(stack, inventory, side, slot, remaining, simulate)) {
            remaining -= stackSize - stack.stackSize
            success = true
          }
        }
      }

      for (slot <- range) {
        val stackSize = stack.stackSize
        if ((inventory.getStackInSlot(slot) == null) && insertIntoInventorySlot(stack, inventory, side, slot, remaining, simulate)) {
          remaining -= stackSize - stack.stackSize
          success = true
        }
      }

      success
    }

  /**
   * Extracts a slot from an inventory.
   * </p>
   * This will try to extract a stack from any inventory slot. It will iterate
   * all slots until an item can be extracted from a slot.
   * <p/>
   * This uses the <tt>extractFromInventorySlot</tt> method, and therefore
   * handles special cases such as sided inventories and stack size limits.
   * <p/>
   * This returns <tt>true</tt> if at least one item was extracted.
   */
  def extractFromInventory(consumer: (ItemStack) => Unit, inventory: IInventory, side: EnumFacing, limit: Int = 64) =
    (0 until inventory.getSizeInventory).exists(slot => extractFromInventorySlot(consumer, inventory, side, slot, limit))

  /**
   * Utility method for calling <tt>insertIntoInventory</tt> on an inventory
   * in the world.
   */
  def insertIntoInventoryAt(stack: ItemStack, position: BlockPosition, side: EnumFacing, limit: Int = 64, simulate: Boolean = false): Boolean =
    inventoryAt(position).exists(insertIntoInventory(stack, _, Option(side), limit, simulate))

  /**
   * Utility method for calling <tt>extractFromInventory</tt> on an inventory
   * in the world.
   */
  def extractFromInventoryAt(consumer: (ItemStack) => Unit, position: BlockPosition, side: EnumFacing, limit: Int = 64) =
    inventoryAt(position).exists(extractFromInventory(consumer, _, side, limit))

  /**
   * Utility method for dropping contents from a single inventory slot into
   * the world.
   */
  def dropSlot(position: BlockPosition, inventory: IInventory, slot: Int, count: Int, direction: Option[EnumFacing] = None) = {
    Option(inventory.decrStackSize(slot, count)) match {
      case Some(stack) if stack.stackSize > 0 => spawnStackInWorld(position, stack, direction); true
      case _ => false
    }
  }

  /**
   * Utility method for dumping all inventory contents into the world.
   */
  def dropAllSlots(position: BlockPosition, inventory: IInventory) {
    for (slot <- 0 until inventory.getSizeInventory) {
      Option(inventory.getStackInSlot(slot)) match {
        case Some(stack) if stack.stackSize > 0 =>
          inventory.setInventorySlotContents(slot, null)
          spawnStackInWorld(position, stack)
        case _ => // Nothing.
      }
    }
  }

  /**
   * Utility method for spawning an item stack in the world.
   */
  def spawnStackInWorld(position: BlockPosition, stack: ItemStack, direction: Option[EnumFacing] = None): EntityItem = position.world match {
    case Some(world) =>
      val rng = world.rand
      val (ox, oy, oz) = direction.fold((0, 0, 0))(d => (d.getFrontOffsetX, d.getFrontOffsetY, d.getFrontOffsetZ))
      val (tx, ty, tz) = (
        0.1 * (rng.nextDouble - 0.5) + ox * 0.65,
        0.1 * (rng.nextDouble - 0.5) + oy * 0.75 + (ox + oz) * 0.25,
        0.1 * (rng.nextDouble - 0.5) + oz * 0.65)
      val dropPos = position.offset(0.5 + tx, 0.5 + ty, 0.5 + tz)
      val entity = new EntityItem(world, dropPos.xCoord, dropPos.yCoord, dropPos.zCoord, stack.copy())
      entity.motionX = 0.0125 * (rng.nextDouble - 0.5) + ox * 0.03
      entity.motionY = 0.0125 * (rng.nextDouble - 0.5) + oy * 0.08 + (ox + oz) * 0.03
      entity.motionZ = 0.0125 * (rng.nextDouble - 0.5) + oz * 0.03
      entity.setPickupDelay(15)
      world.spawnEntityInWorld(entity)
      entity
    case _ => null
  }

}
