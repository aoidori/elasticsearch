/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.downsample.DownsampleAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.tracing.Tracer;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xpack.core.downsample.DownsampleIndexerAction;
import org.elasticsearch.xpack.core.downsample.DownsampleShardPersistentTaskState;
import org.elasticsearch.xpack.core.downsample.DownsampleShardTask;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class Downsample extends Plugin implements ActionPlugin, PersistentTaskPlugin {

    public static final String DOWSAMPLE_TASK_THREAD_POOL_NAME = "downsample_indexing";
    private static final int DOWNSAMPLE_TASK_THREAD_POOL_QUEUE_SIZE = 256;

    private IndicesService indicesService;

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        final FixedExecutorBuilder downsample = new FixedExecutorBuilder(
            settings,
            DOWSAMPLE_TASK_THREAD_POOL_NAME,
            ThreadPool.oneEighthAllocatedProcessors(EsExecutors.allocatedProcessors(settings)),
            DOWNSAMPLE_TASK_THREAD_POOL_QUEUE_SIZE,
            "xpack.downsample.thread_pool",
            EsExecutors.TaskTrackingConfig.DO_NOT_TRACK
        );
        return List.of(downsample);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return List.of(
            new ActionHandler<>(DownsampleIndexerAction.INSTANCE, TransportDownsampleIndexerAction.class),
            new ActionHandler<>(DownsampleAction.INSTANCE, TransportDownsampleAction.class)
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return List.of(new RestDownsampleAction());
    }

    @Override
    public List<PersistentTasksExecutor<?>> getPersistentTasksExecutor(
        ClusterService clusterService,
        ThreadPool threadPool,
        Client client,
        SettingsModule settingsModule,
        IndexNameExpressionResolver expressionResolver
    ) {
        return List.of(
            new DownsampleShardPersistentTaskExecutor(
                client,
                this.indicesService,
                DownsampleShardTask.TASK_NAME,
                DOWSAMPLE_TASK_THREAD_POOL_NAME
            )
        );
    }

    @Override
    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        return List.of(
            new NamedXContentRegistry.Entry(
                PersistentTaskState.class,
                new ParseField(DownsampleShardPersistentTaskState.NAME),
                DownsampleShardPersistentTaskState::fromXContent
            ),
            new NamedXContentRegistry.Entry(
                PersistentTaskParams.class,
                new ParseField(DownsampleShardTaskParams.NAME),
                DownsampleShardTaskParams::fromXContent
            )
        );
    }

    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return List.of(
            new NamedWriteableRegistry.Entry(
                PersistentTaskState.class,
                DownsampleShardPersistentTaskState.NAME,
                DownsampleShardPersistentTaskState::readFromStream
            ),
            new NamedWriteableRegistry.Entry(
                PersistentTaskParams.class,
                DownsampleShardTaskParams.NAME,
                DownsampleShardTaskParams::readFromStream
            )
        );
    }

    @Override
    public Collection<Object> createComponents(
        final Client client,
        final ClusterService clusterService,
        final ThreadPool threadPool,
        final ResourceWatcherService resourceWatcherService,
        final ScriptService scriptService,
        final NamedXContentRegistry xContentRegistry,
        final Environment environment,
        final NodeEnvironment nodeEnvironment,
        final NamedWriteableRegistry namedWriteableRegistry,
        final IndexNameExpressionResolver indexNameExpressionResolver,
        final Supplier<RepositoriesService> repositoriesServiceSupplier,
        final Tracer tracer,
        final AllocationService allocationService,
        final IndicesService indicesService
    ) {
        final Collection<Object> components = super.createComponents(
            client,
            clusterService,
            threadPool,
            resourceWatcherService,
            scriptService,
            xContentRegistry,
            environment,
            nodeEnvironment,
            namedWriteableRegistry,
            indexNameExpressionResolver,
            repositoriesServiceSupplier,
            tracer,
            allocationService,
            indicesService
        );

        this.indicesService = indicesService;
        return components;
    }
}
