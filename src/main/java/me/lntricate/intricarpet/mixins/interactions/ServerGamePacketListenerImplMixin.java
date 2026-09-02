package me.lntricate.intricarpet.mixins.interactions;

import carpet.CarpetSettings;
import me.lntricate.intricarpet.interactions.Interaction;
import me.lntricate.intricarpet.interfaces.IServerPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(
        method = "handleUseItemOn",
        at =
            @At(
                value = "INVOKE",
                target =
                    "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private void beforeInteractBlock(ServerboundUseItemOnPacket _packet, CallbackInfo _ci) {
        if (!((IServerPlayer) this.player).getInteraction(Interaction.UPDATES)) {
            CarpetSettings.impendingFillSkipUpdates.set(true);
        }
    }

    @Inject(
        method = "handleUseItemOn",
        at =
            @At(
                value = "INVOKE",
                target =
                    "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
                shift = Shift.AFTER))
    private void afterInteractBlock(ServerboundUseItemOnPacket _packet, CallbackInfo _ci) {
        CarpetSettings.impendingFillSkipUpdates.set(false);
    }

    @Inject(
        method = "handleUseItem",
        at =
            @At(
                value = "INVOKE",
                target =
                    "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private void beforeInteractItem(ServerboundUseItemPacket _packet, CallbackInfo _ci) {
        if (!((IServerPlayer) this.player).getInteraction(Interaction.UPDATES)) {
            CarpetSettings.impendingFillSkipUpdates.set(true);
        }
    }

    @Inject(
        method = "handleUseItem",
        at =
            @At(
                value = "INVOKE",
                target =
                    "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
                shift = Shift.AFTER))
    private void afterInteractItem(ServerboundUseItemPacket _packet, CallbackInfo _ci) {
        CarpetSettings.impendingFillSkipUpdates.set(false);
    }

    // 1.19 added the `Direction` argument's sequence number, so the descriptor the two injectors
    // below target gained a second `int`.
    #[cfg(feature = "since-1.19")]
    private static final String HANDLE_BLOCK_BREAK_ACTION =
        "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V";

    #[cfg(not(feature = "since-1.19"))]
    private static final String HANDLE_BLOCK_BREAK_ACTION =
        "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;I)V";

    @Inject(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE", target = HANDLE_BLOCK_BREAK_ACTION))
    private void beforeBreakBlock(ServerboundPlayerActionPacket _packet, CallbackInfo _ci) {
        if (!((IServerPlayer) this.player).getInteraction(Interaction.UPDATES)) {
            CarpetSettings.impendingFillSkipUpdates.set(true);
        }
    }

    @Inject(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE", target = HANDLE_BLOCK_BREAK_ACTION, shift = Shift.AFTER))
    private void afterBreakBlock(ServerboundPlayerActionPacket _packet, CallbackInfo _ci) {
        CarpetSettings.impendingFillSkipUpdates.set(false);
    }
}
