package dev.lorenzzz.smplibrary.modules.shop;

import java.util.List;
import java.util.Map;

public record ShopConfig(
        MainInvConfig mainInventory,
        StackItemConfig addStackItem,
        StackItemConfig removeStackItem,
        StackItemConfig setToMinItem,
        StackItemConfig setToMaxItem,
        Map<String, CategoryConfig> categories,
        boolean keepOpenAfterPurchase,
        PrintConfig printConsole,
        boolean antiDupe,
        boolean debug,
        Map<String, TemplateItem> items,
        MessagesConfig messages,
        SoundConfig openShopSound,
        SoundConfig openCategorySound,
        SoundConfig navigatePageSound,
        SoundConfig selectItemSound,
        SoundConfig confirmPurchaseSound,
        SoundConfig cancelPurchaseSound,
        SoundConfig purchaseErrorSound,
        SoundConfig backButtonSound
) {}

record MainInvConfig(
        String title,
        List<String> layout
) {}

record StackItemConfig(
        String displayName,
        String type
) {}

record CategoryConfig(
        boolean enabled,
        int slot,
        int rows,
        IconConfig icon,
        String inventoryName,
        List<String> layout,
        Map<String, ProductConfig> products
) {}

record IconConfig(
        String displayName,
        String type,
        List<String> lore
) {}

record ProductConfig(
        String material,
        int price,
        List<String> commands,
        List<String> lore,
        String displayName
) {}

record TemplateItem(
        String material,
        String name,
        List<String> lore
) {}

record MessagesConfig(
        String insufficientFunds,
        String inventoryFull,
        String purchaseSuccess,
        String transactionError,
        String shopReloaded,
        String confirmTitle,
        String itemPrice,
        String itemAmount,
        String confirmQuantity,
        String itemClick
) {}

record PrintConfig(
        boolean enabled
) {}

record SoundConfig(
        String sound,
        float volume,
        float pitch,
        boolean enabled
) {}