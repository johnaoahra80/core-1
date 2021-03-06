/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.console.client.domain.topology;

import com.google.gwt.event.logical.shared.CloseEvent;
import com.google.gwt.event.logical.shared.CloseHandler;
import com.google.gwt.user.client.Random;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.Presenter;
import com.gwtplatform.mvp.client.annotations.NameToken;
import com.gwtplatform.mvp.client.annotations.ProxyCodeSplit;
import com.gwtplatform.mvp.client.proxy.Place;
import com.gwtplatform.mvp.client.proxy.PlaceManager;
import com.gwtplatform.mvp.client.proxy.PlaceRequest;
import com.gwtplatform.mvp.client.proxy.Proxy;
import com.gwtplatform.mvp.client.proxy.RevealContentEvent;
import org.jboss.as.console.client.core.NameTokens;
import org.jboss.as.console.client.core.SuspendableView;
import org.jboss.as.console.client.domain.model.HostInformationStore;
import org.jboss.as.console.client.domain.model.Server;
import org.jboss.as.console.client.domain.model.ServerGroupStore;
import org.jboss.as.console.client.domain.model.ServerInstance;
import org.jboss.as.console.client.domain.model.SimpleCallback;
import org.jboss.as.console.client.domain.model.impl.LifecycleOperation;
import org.jboss.as.console.client.domain.model.impl.ServerGroupLifecycleCallback;
import org.jboss.as.console.client.domain.model.impl.ServerInstanceLifecycleCallback;
import org.jboss.as.console.client.domain.runtime.DomainRuntimePresenter;
import org.jboss.as.console.client.shared.BeanFactory;
import org.jboss.as.console.client.shared.runtime.ext.Extension;
import org.jboss.as.console.client.shared.runtime.ext.ExtensionManager;
import org.jboss.as.console.client.shared.runtime.ext.LoadExtensionCmd;
import org.jboss.as.console.spi.AccessControl;
import org.jboss.ballroom.client.widgets.window.DefaultWindow;
import org.jboss.dmr.client.dispatch.DispatchAsync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.jboss.as.console.client.domain.model.ServerFlag.RELOAD_REQUIRED;
import static org.jboss.as.console.client.domain.model.ServerFlag.RESTART_REQUIRED;

public class TopologyPresenter extends
        Presenter<TopologyPresenter.MyView, TopologyPresenter.MyProxy>
        implements ExtensionManager
{

    /**
     * We cannot expect a valid {@code {selected.server}} when the access control rules are evaluated by
     * the security service (race condition). So do not use them in the annotaions below!
     * @author Harald Pehl
     * @date 10/15/12
     */
    @ProxyCodeSplit
    @NameToken(NameTokens.Topology)
    @AccessControl(resources = {
            "/server-group=*",
            //"/{selected.host}/server=*",  https://issues.jboss.org/browse/WFLY-1997
    }, recursive = false)
    public interface MyProxy extends Proxy<TopologyPresenter>, Place
    {
    }


    public interface MyView extends SuspendableView
    {
        void setPresenter(TopologyPresenter presenter);
        void updateHosts(final SortedSet<ServerGroup> groups, final int hostIndex);
        void setExtensions(List<Extension> extensions);
    }


    public static final int VISIBLE_HOSTS_COLUMNS = 3;

    private final PlaceManager placeManager;
    private final ServerGroupStore serverGroupStore;
    private final HostInformationStore hostInfoStore;
    private final BeanFactory beanFactory;
    private final Map<String,ServerGroup> serverGroups;
    private LoadExtensionCmd loadExtensionCmd;
    private boolean fake;
    private int hostIndex;


    @Inject
    public TopologyPresenter(final EventBus eventBus, final MyView view,
                             final MyProxy proxy, final PlaceManager placeManager,
                             final HostInformationStore hostInfoStore, final ServerGroupStore serverGroupStore,
                             final BeanFactory beanFactory, DispatchAsync dispatcher)
    {
        super(eventBus, view, proxy);
        this.placeManager = placeManager;
        this.serverGroupStore = serverGroupStore;
        this.hostInfoStore = hostInfoStore;
        this.beanFactory = beanFactory;

        this.loadExtensionCmd = new LoadExtensionCmd(dispatcher, beanFactory);
        this.serverGroups = new HashMap<String, ServerGroup>();
        this.fake = false;
        this.hostIndex = 0;
    }


    // ------------------------------------------------------ presenter lifecycle

    @Override
    protected void onBind()
    {
        super.onBind();
        getView().setPresenter(this);

    }

    @Override
    protected void onReset()
    {
        super.onReset();
        loadTopology();
        loadExtensions();
    }

    @Override
    public void prepareFromRequest(final PlaceRequest request)
    {
        super.prepareFromRequest(request);
        fake = Boolean.valueOf(request.getParameter("fake", "false"));
        hostIndex = Integer.parseInt(request.getParameter("hostIndex", "0"));
    }

    @Override
    protected void revealInParent()
    {
        RevealContentEvent.fire(this, DomainRuntimePresenter.TYPE_MainContent, this);
    }


    // ------------------------------------------------------ public presenter API


    public void loadTopology()
    {
        if (fake)
        {
            getView().updateHosts(deriveGroups(generateFakeDomain()), hostIndex);
        }
        else
        {
            hostInfoStore.loadHostsAndServerInstances(new SimpleCallback<List<HostInfo>>()
            {
                @Override
                public void onSuccess(final List<HostInfo> result)
                {
                    getView().updateHosts(deriveGroups(result), hostIndex);
                }
            });
        }
    }

    public void requestHostIndex(int hostIndex)
    {
        PlaceRequest placeRequest = new PlaceRequest(NameTokens.Topology).with("hostIndex", String.valueOf(hostIndex));
        if (fake)
        {
            placeRequest = placeRequest.with("fake", String.valueOf(fake));
        }
        placeManager.revealPlace(placeRequest);
    }

    public void onServerInstanceLifecycle(final String host, final String server, final LifecycleOperation op)
    {
        ServerInstanceLifecycleCallback lifecycleCallback = new ServerInstanceLifecycleCallback(
                hostInfoStore, host, server, op, new SimpleCallback<Server>()
        {
            @Override
            public void onSuccess(final Server server)
            {
                loadTopology();
            }
        });
        switch (op)
        {
            case START:
                hostInfoStore.startServer(host, server, true, lifecycleCallback);
                break;
            case STOP:
                hostInfoStore.startServer(host, server, false, lifecycleCallback);
                break;
            case RELOAD:
                hostInfoStore.reloadServer(host, server, lifecycleCallback);
                break;
            case RESTART:
                break;
        }
    }

    public void onGroupLifecycle(final String group, final LifecycleOperation op)
    {
        ServerGroup serverGroup = serverGroups.get(group);
        if (serverGroup != null)
        {
            ServerGroupLifecycleCallback lifecycleCallback = new ServerGroupLifecycleCallback(hostInfoStore,
                    serverGroup.serversPerHost, op, new SimpleCallback<List<Server>>()
            {
                @Override
                public void onSuccess(final List<Server> result)
                {
                    loadTopology();
                }
            });
            switch (op)
            {
                case START:
                    serverGroupStore.startServerGroup(group, lifecycleCallback);
                    break;
                case STOP:
                    serverGroupStore.stopServerGroup(group, lifecycleCallback);
                    break;
                case RELOAD:
                    break;
                case RESTART:
                    serverGroupStore.restartServerGroup(group, lifecycleCallback);
                    break;
            }
        }
    }


    // ------------------------------------------------------ helper methods

    /**
     * Builds {@link ServerGroup} instances and populates the map {@link #serverGroups}
     * @param hosts
     */
    private SortedSet<ServerGroup> deriveGroups(List<HostInfo> hosts)
    {
        serverGroups.clear();
        for (HostInfo host : hosts)
        {
            List<ServerInstance> serverInstances = host.getServerInstances();
            for (ServerInstance server : serverInstances)
            {
                String group = server.getGroup();
                String profile = server.getProfile();
                ServerGroup serverGroup = serverGroups.get(group);
                if (serverGroup == null)
                {
                    serverGroup = new ServerGroup(group, profile);
                    serverGroup.fill(hosts);
                    serverGroups.put(group, serverGroup);
                }
            }
        }
        return new TreeSet<ServerGroup>(serverGroups.values());
    }

    private List<HostInfo> generateFakeDomain()
    {
        String[] hostNames = new String[]{"lightning", "eeak-a-mouse", "dirty-harry"};
        String[] groupNames = new String[]{"staging", "production", "messaging-back-server-test", "uat", "messaging", "backoffice", "starlight"};
        String[] profiles = new String[]{"default", "default", "default", "messaging", "web", "full-ha", "full-ha"};

        int numHosts = 13;
        final List<HostInfo> hostInfos = new ArrayList<HostInfo>();

        for (int i = 0; i < numHosts; i++)
        {
            // host info
            String name = hostNames[Random.nextInt(2)] + "-" + i;
            boolean isController = (i < 1);

            HostInfo host = new HostInfo(name, isController);
            host.setServerInstances(new ArrayList<ServerInstance>());

            // server instances
            for (int x = 0; x < (Random.nextInt(5) + 1); x++)
            {
                int groupIndex = Random.nextInt(groupNames.length - 1);
                ServerInstance serverInstance = beanFactory.serverInstance().as();
                serverInstance.setGroup(groupNames[groupIndex]);
                serverInstance.setRunning((groupIndex % 2 == 0));
                if (serverInstance.isRunning())
                {
                    if (Random.nextBoolean())
                    {
                        serverInstance.setFlag(Random.nextBoolean() ? RESTART_REQUIRED : RELOAD_REQUIRED);
                    }
                    else
                    {
                        serverInstance.setFlag(null);
                    }
                }
                serverInstance.setName(groupNames[groupIndex] + "-" + x);
                serverInstance.setSocketBindings(Collections.<String, String>emptyMap());
                serverInstance.setInterfaces(Collections.<String, String>emptyMap());

                host.getServerInstances().add(serverInstance);
            }
            hostInfos.add(host);
        }
        return hostInfos;
    }

    public void loadExtensions()
    {
        loadExtensionCmd.execute(new SimpleCallback<List<Extension>>() {
            @Override
            public void onSuccess(List<Extension> extensions) {
                getView().setExtensions(extensions);
            }
        });
    }

    public void onDumpVersions() {

        loadExtensionCmd.dumpVersions(new SimpleCallback<String>() {
            @Override
            public void onSuccess(String s) {
                showVersionInfo(s);
            }
        });

    }

    private void showVersionInfo(String json)
    {
        DefaultWindow window = new DefaultWindow("Management Model Versions");
        window.setWidth(480);
        window.setHeight(360);
        window.addCloseHandler(new CloseHandler<PopupPanel>() {
            @Override
            public void onClose(CloseEvent<PopupPanel> event) {

            }
        });

        TextArea textArea = new TextArea();
        textArea.setStyleName("fill-layout");
        textArea.setText(json);

        window.setWidget(textArea);

        window.setGlassEnabled(true);
        window.center();
    }
}
