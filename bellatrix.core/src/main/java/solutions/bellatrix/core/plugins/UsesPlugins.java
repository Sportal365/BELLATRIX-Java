package solutions.bellatrix.core.plugins;

import solutions.bellatrix.core.utilities.SingletonFactory;

public abstract class UsesPlugins {
    public <T extends Plugin> void addPlugin(Class<T> plugin, Object... args) {
        PluginExecutionEngine.addPlugin(SingletonFactory.getRequiredInstance(plugin, args));
    }

    public <T extends Listener> void addListener(Class<T> listener, Object... args) {
        SingletonFactory.getRequiredInstance(listener, args).addListener();
    }
}
