package dev.flop.build1.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets us ask an item exactly what state it would place (signs/torches/banners pick wall vs standing here),
 * instead of guessing from the block alone.
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState build1$getPlacementState(BlockPlaceContext ctx);
}
