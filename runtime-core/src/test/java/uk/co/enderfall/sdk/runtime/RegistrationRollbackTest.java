package uk.co.enderfall.sdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.co.enderfall.sdk.api.ResourceId;
import uk.co.enderfall.sdk.api.command.CommandSpec;
import uk.co.enderfall.sdk.api.network.PacketDirection;
import uk.co.enderfall.sdk.api.platform.CapabilitySet;
import uk.co.enderfall.sdk.api.platform.Environment;
import uk.co.enderfall.sdk.api.platform.Loader;
import uk.co.enderfall.sdk.api.platform.MinecraftVersion;
import uk.co.enderfall.sdk.api.platform.PlatformInfo;
import uk.co.enderfall.sdk.api.registry.BlockSpec;
import uk.co.enderfall.sdk.api.registry.CreativeTabSpec;
import uk.co.enderfall.sdk.api.registry.ItemSpec;

/**
 * Registration IDs are permanent for the life of a process. Every registrar therefore has to
 * release its dedup entry when native registration fails, otherwise one transient loader failure
 * silently makes that ID unregisterable and reports a misleading duplicate on every retry.
 */
class RegistrationRollbackTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aFailedItemRegistrationReleasesItsId() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);
        ResourceId id = ResourceId.of("test_mod", "ingot");

        adapter.fail = true;
        assertThrows(IllegalStateException.class,
                () -> context.items().register(id, ItemSpec.builder().build()));

        adapter.fail = false;
        assertNotNull(context.items().register(id, ItemSpec.builder().build()));
    }

    @Test
    void aFailedBlockRegistrationReleasesItsIdAndSpec() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);
        ResourceId id = ResourceId.of("test_mod", "machine");

        adapter.failBlocks = true;
        assertThrows(IllegalStateException.class,
                () -> context.blocks().register(id, BlockSpec.builder().build()));

        adapter.failBlocks = false;
        assertNotNull(context.blocks().register(id, BlockSpec.builder().build()));
    }

    @Test
    void aRejectedBlockWithItemDoesNotBurnTheItemId() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);

        // A duplicate block is rejected after the item ID has already been reserved.
        context.blocks().register(ResourceId.of("test_mod", "machine"), BlockSpec.builder().build());
        assertThrows(IllegalStateException.class, () -> context.blocks().registerWithItem(
                "machine", BlockSpec.builder().build(), ItemSpec.builder().build()));

        // The reserved item ID must still be usable, rather than reporting "Duplicate item ID".
        assertNotNull(context.items().register(ResourceId.of("test_mod", "machine"),
                ItemSpec.builder().build()));
    }

    @Test
    void aFailedBlockWithItemRegistrationReleasesTheItemId() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);
        adapter.failBlocks = true;

        assertThrows(IllegalStateException.class, () -> context.blocks().registerWithItem(
                "machine", BlockSpec.builder().build(), ItemSpec.builder().build()));

        adapter.failBlocks = false;
        assertNotNull(context.items().register(ResourceId.of("test_mod", "machine"),
                ItemSpec.builder().build()));
    }

    @Test
    void aFailedCreativeTabRegistrationReleasesItsId() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);
        ResourceId id = ResourceId.of("test_mod", "tab");

        adapter.fail = true;
        assertThrows(IllegalStateException.class,
                () -> context.creativeTabs().register(id, CreativeTabSpec.builder("test_mod.tab", new uk.co.enderfall.sdk.api.registry.ItemRef(ResourceId.of("test_mod", "ingot"))).build()));

        adapter.fail = false;
        assertNotNull(context.creativeTabs().register(id, CreativeTabSpec.builder("test_mod.tab", new uk.co.enderfall.sdk.api.registry.ItemRef(ResourceId.of("test_mod", "ingot"))).build()));
    }

    @Test
    void aFailedCommandRegistrationReleasesItsName() {
        FailingAdapter adapter = new FailingAdapter(temporaryDirectory);
        RuntimeModContext context = new RuntimeModContext("test_mod", adapter);

        adapter.fail = true;
        assertThrows(IllegalStateException.class,
                () -> context.commands().register(CommandSpec.builder("reload").executes(ignored -> 0).build()));

        adapter.fail = false;
        context.commands().register(CommandSpec.builder("reload").executes(ignored -> 0).build());
        assertEquals(1, adapter.registeredCommands.size());
    }

    private static final class FailingAdapter implements PlatformAdapter {
        private final Path directory;
        private final List<CommandSpec> registeredCommands = new ArrayList<>();
        private boolean fail;
        private boolean failBlocks;

        private FailingAdapter(Path directory) {
            this.directory = directory;
        }

        @Override public PlatformInfo platformInfo() { return new TestPlatformInfo(); }
        @Override public CapabilitySet capabilities() { return new ImmutableCapabilitySet(java.util.Set.of()); }
        @Override public Path commonConfigDirectory() { return directory; }
        @Override public Path serverConfigDirectory() { return directory.resolve("serverconfig"); }
        @Override public void registerItem(ResourceId id, ItemSpec spec) {
            if (fail) throw new IllegalStateException("native item registration failed");
        }
        @Override public void registerBlock(ResourceId id, BlockSpec spec, ItemSpec blockItemSpec) {
            if (failBlocks) throw new IllegalStateException("native block registration failed");
        }
        @Override public void registerCreativeTab(ResourceId id, CreativeTabSpec spec) {
            if (fail) throw new IllegalStateException("native creative tab registration failed");
        }
        @Override public void registerCommand(CommandSpec command) {
            if (fail) throw new IllegalStateException("native command registration failed");
            registeredCommands.add(command);
        }
        @Override public void registerPayload(ResourceId id, PacketDirection direction, int maximumBytes,
                                              PayloadReceiver receiver) { }
        @Override public void sendToServer(ResourceId id, byte[] payload) { }
        @Override public void sendToPlayer(UUID playerId, ResourceId id, byte[] payload) { }
        @Override public void sendToAll(ResourceId id, byte[] payload) { }
    }

    private record TestPlatformInfo() implements PlatformInfo {
        @Override public Loader loader() { return Loader.FABRIC; }
        @Override public MinecraftVersion minecraftVersion() { return new MinecraftVersion("1.21.4"); }
        @Override public Environment environment() { return Environment.DEDICATED_SERVER; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public Optional<String> modVersion(String modId) { return Optional.empty(); }
    }
}