package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /settings GUI costruita con la Dialog API di Paper 1.21.7+.
 *
 * <p>Ogni voce di {@code settings.yml} diventa un {@link ActionButton} in un
 * dialog di tipo {@code multiAction}. Il clic viene gestito in-place tramite
 * {@link DialogAction#customClick} che esegue i comandi configurati per quel
 * pulsante, togglea lo stato ON/OFF e riapre il dialog aggiornato istantaneamente.</p>
 *
 * <p>Lo stato ON/OFF vive in {@link SettingsStateStore}, che lo salva sul database:
 * prima veniva tenuto solo in memoria e ogni sessione ripartiva con tutto su ON.</p>
 */
public final class SettingsDialog {

    private final SettingsConfig config;
    private final Smp_library plugin;
    private final SettingsStateStore store;

    public SettingsDialog(Smp_library plugin, SettingsConfig config, SettingsStateStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
    }

    /** Apre il dialog, attendendo se serve che i toggle salvati siano stati letti. */
    public void show(Player player) {
        store.whenLoaded(player.getUniqueId(), () -> {
            if (player.isOnline()) renderDialog(player);
        });
    }

    // ------------------------------------------------------------------

    private void renderDialog(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, SettingsItemConfig> entry : config.items().entrySet()) {
            String key = entry.getKey();
            SettingsItemConfig item = entry.getValue();
            boolean enabled = store.get(player.getUniqueId(), key);
            buttons.add(buildButton(player, key, item, enabled));
        }

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(C.mm(config.name()))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("Seleziona un'opzione per attivarla o disattivarla.")
                                        .color(NamedTextColor.GRAY))))
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(3)
                        .build())
        );

        player.showDialog(dialog);
    }

    private ActionButton buildButton(Player player, String key, SettingsItemConfig item, boolean enabled) {
        ItemConfig ic = item.item();

        Component baseLabel = C.mm(ic.name());
        if (!baseLabel.hasStyling()
                && baseLabel.style().decoration(TextDecoration.BOLD) == TextDecoration.State.NOT_SET) {
            baseLabel = baseLabel.decorate(TextDecoration.BOLD);
        }

        Component label;
        // ah_options non e' un toggle: nessun suffisso ON/OFF
        if (key.equals("ah_options")) {
            label = baseLabel;
        } else {
            // Suffix ON / OFF affiancato al titolo
            Component stateLabel = enabled
                    ? Component.text(" ON").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    : Component.text(" OFF").color(NamedTextColor.RED).decorate(TextDecoration.BOLD);
            label = baseLabel.append(stateLabel);
        }
        Component tooltip = buildTooltip(ic.lore());

        return ActionButton.builder(label)
                .tooltip(tooltip)
                .width(200)
                .action(DialogAction.customClick(
                        (view, audience) -> handleClick(player, key, item),
                        ClickCallback.Options.builder()
                                .uses(1) // Necessario per riaprire il dialog correttamente ad ogni click
                                .build()
                ))
                .build();
    }

    private Component buildTooltip(List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            return Component.text("Clicca per attivare/disattivare.").color(NamedTextColor.GREEN);
        }
        Component joined = Component.empty();
        for (int i = 0; i < lore.size(); i++) {
            Component line = C.mm(lore.get(i));
            if (i > 0) joined = joined.append(Component.newline());
            joined = joined.append(line);
        }
        return joined;
    }

    private void handleClick(Player player, String key, SettingsItemConfig item) {
        // Assicuriamoci che l'esecuzione avvenga sul thread principale di Bukkit
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleClick(player, key, item));
            return;
        }

        // ah_options non e' un toggle: esegue i comandi e chiude, senza toccare lo stato
        if (key.equals("ah_options")) {
            String name = player.getName();
            for (String cmd : item.commands()) {
                try {
                    Bukkit.dispatchCommand(player, cmd.replace("%player%", name));
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[Settings] Errore comando '" + cmd + "': " + e.getMessage());
                }
            }
            return;
        }

        // Toggle dello stato (salvato subito, cosi' sopravvive al logout)
        store.set(player.getUniqueId(), key, !store.get(player.getUniqueId(), key));

        // Esegue i comandi configurati
        String name = player.getName();
        for (String cmd : item.commands()) {
            try {
                Bukkit.dispatchCommand(player, cmd.replace("%player%", name));
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Settings] Errore comando '" + cmd + "': " + e.getMessage());
            }
        }

        // Riapre il dialog istantaneamente sullo stesso tick per evitare il flickering (chiusura visiva)
        renderDialog(player);
    }
}