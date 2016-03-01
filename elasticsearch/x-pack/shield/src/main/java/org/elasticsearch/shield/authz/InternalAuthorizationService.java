/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.search.ClearScrollAction;
import org.elasticsearch.action.search.SearchScrollAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.search.action.SearchTransportService;
import org.elasticsearch.shield.SystemUser;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.XPackUser;
import org.elasticsearch.shield.audit.AuditTrail;
import org.elasticsearch.shield.authc.AnonymousService;
import org.elasticsearch.shield.authc.AuthenticationFailureHandler;
import org.elasticsearch.shield.authz.accesscontrol.IndicesAccessControl;
import org.elasticsearch.shield.authz.indicesresolver.DefaultIndicesAndAliasesResolver;
import org.elasticsearch.shield.authz.indicesresolver.IndicesAndAliasesResolver;
import org.elasticsearch.shield.authz.permission.ClusterPermission;
import org.elasticsearch.shield.authz.permission.GlobalPermission;
import org.elasticsearch.shield.authz.permission.Role;
import org.elasticsearch.shield.authz.permission.RunAsPermission;
import org.elasticsearch.shield.authz.privilege.ClusterPrivilege;
import org.elasticsearch.shield.authz.privilege.IndexPrivilege;
import org.elasticsearch.shield.authz.store.RolesStore;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.elasticsearch.shield.support.Exceptions.authorizationError;

/**
 *
 */
public class InternalAuthorizationService extends AbstractComponent implements AuthorizationService {

    public static final String INDICES_PERMISSIONS_KEY = "_indices_permissions";
    static final String ORIGINATING_ACTION_KEY = "_originating_action_name";

    private final ClusterService clusterService;
    private final RolesStore rolesStore;
    private final AuditTrail auditTrail;
    private final IndicesAndAliasesResolver[] indicesAndAliasesResolvers;
    private final AnonymousService anonymousService;
    private final AuthenticationFailureHandler authcFailureHandler;
    private final ThreadContext threadContext;

    @Inject
    public InternalAuthorizationService(Settings settings, RolesStore rolesStore, ClusterService clusterService,
                                        AuditTrail auditTrail, AnonymousService anonymousService,
                                        AuthenticationFailureHandler authcFailureHandler, ThreadPool threadPool) {
        super(settings);
        this.rolesStore = rolesStore;
        this.clusterService = clusterService;
        this.auditTrail = auditTrail;
        this.indicesAndAliasesResolvers = new IndicesAndAliasesResolver[]{
                new DefaultIndicesAndAliasesResolver(this)
        };
        this.anonymousService = anonymousService;
        this.authcFailureHandler = authcFailureHandler;
        this.threadContext = threadPool.getThreadContext();
    }

    @Override
    public List<String> authorizedIndicesAndAliases(User user, String action) {
        Predicate<String> predicate;
        if (XPackUser.is(user)) {
            predicate = XPackUser.ROLE.indices().allowedIndicesMatcher(action);
        } else {
            String[] rolesNames = user.roles();
            if (rolesNames.length == 0) {
                return Collections.emptyList();
            }
            List<Predicate<String>> predicates = new ArrayList<>();
            for (String roleName : rolesNames) {
                Role role = rolesStore.role(roleName);
                if (role != null) {
                    predicates.add(role.indices().allowedIndicesMatcher(action));
                }
            }
            predicate = predicates.stream().reduce(s -> false, (p1, p2) -> p1.or(p2));
        }

        List<String> indicesAndAliases = new ArrayList<>();
        MetaData metaData = clusterService.state().metaData();
        // TODO: can this be done smarter? I think there are usually more indices/aliases in the cluster then indices defined a roles?
        for (Map.Entry<String, AliasOrIndex> entry : metaData.getAliasAndIndexLookup().entrySet()) {
            String aliasOrIndex = entry.getKey();
            if (predicate.test(aliasOrIndex)) {
                indicesAndAliases.add(aliasOrIndex);
            }
        }

        return Collections.unmodifiableList(indicesAndAliases);
    }

    @Override
    public void authorize(User user, String action, TransportRequest request) throws ElasticsearchSecurityException {
        // prior to doing any authorization lets set the originating action in the context only
        setOriginatingAction(action);

        // first we need to check if the user is the system. If it is, we'll just authorize the system access
        if (SystemUser.is(user)) {
            if (SystemUser.isAuthorized(action)) {
                setIndicesAccessControl(IndicesAccessControl.ALLOW_ALL);
                grant(user, action, request);
                return;
            }
            throw denial(user, action, request);
        }

        GlobalPermission permission = XPackUser.is(user) ? XPackUser.ROLE : permission(user.roles());

        final boolean isRunAs = user.runAs() != null;
        // permission can be null as it might be that the user's role
        // is unknown
        if (permission == null || permission.isEmpty()) {
            if (isRunAs) {
                // the request is a run as request so we should call the specific audit event for a denied run as attempt
                throw denyRunAs(user, action, request);
            } else {
                throw denial(user, action, request);
            }
        }

        // check if the request is a run as request
        if (isRunAs) {
            // first we must authorize for the RUN_AS action
            RunAsPermission runAs = permission.runAs();
            if (runAs != null && runAs.check(user.runAs().principal())) {
                grantRunAs(user, action, request);
                permission = permission(user.runAs().roles());

                // permission can be null as it might be that the user's role
                // is unknown
                if (permission == null || permission.isEmpty()) {
                    throw denial(user, action, request);
                }
            } else {
                throw denyRunAs(user, action, request);
            }
        }

        // first, we'll check if the action is a cluster action. If it is, we'll only check it
        // against the cluster permissions
        if (ClusterPrivilege.ACTION_MATCHER.test(action)) {
            ClusterPermission cluster = permission.cluster();
            if (cluster != null && cluster.check(action)) {
                setIndicesAccessControl(IndicesAccessControl.ALLOW_ALL);
                grant(user, action, request);
                return;
            }
            throw denial(user, action, request);
        }

        // ok... this is not a cluster action, let's verify it's an indices action
        if (!IndexPrivilege.ACTION_MATCHER.test(action)) {
            throw denial(user, action, request);
        }

        // some APIs are indices requests that are not actually associated with indices. For example,
        // search scroll request, is categorized under the indices context, but doesn't hold indices names
        // (in this case, the security check on the indices was done on the search request that initialized
        // the scroll... and we rely on the signed scroll id to provide security over this request).
        // so we only check indices if indeed the request is an actual IndicesRequest, if it's not,
        // we just grant it if it's a scroll, deny otherwise
        if (!(request instanceof IndicesRequest) && !(request instanceof CompositeIndicesRequest)) {
            if (isScrollRelatedAction(action)) {
                //note that clear scroll shard level actions can originate from a clear scroll all, which doesn't require any
                //indices permission as it's categorized under cluster. This is why the scroll check is performed
                //even before checking if the user has any indices permission.
                grant(user, action, request);
                return;
            }
            assert false : "only scroll related requests are known indices api that don't support retrieving the indices they relate to";
            throw denial(user, action, request);
        }

        if (permission.indices() == null || permission.indices().isEmpty()) {
            throw denial(user, action, request);
        }

        ClusterState clusterState = clusterService.state();
        Set<String> indexNames = resolveIndices(user, action, request, clusterState);
        assert !indexNames.isEmpty() : "every indices request needs to have its indices set thus the resolved indices must not be empty";
        MetaData metaData = clusterState.metaData();
        IndicesAccessControl indicesAccessControl = permission.authorize(action, indexNames, metaData);
        if (!indicesAccessControl.isGranted()) {
            throw denial(user, action, request);
        } else {
            setIndicesAccessControl(indicesAccessControl);
        }

        //if we are creating an index we need to authorize potential aliases created at the same time
        if (IndexPrivilege.CREATE_INDEX_MATCHER.test(action)) {
            assert request instanceof CreateIndexRequest;
            Set<Alias> aliases = ((CreateIndexRequest) request).aliases();
            if (!aliases.isEmpty()) {
                Set<String> aliasesAndIndices = Sets.newHashSet(indexNames);
                for (Alias alias : aliases) {
                    aliasesAndIndices.add(alias.name());
                }
                indicesAccessControl = permission.authorize("indices:admin/aliases", aliasesAndIndices, metaData);
                if (!indicesAccessControl.isGranted()) {
                    throw denial(user, "indices:admin/aliases", request);
                }
                // no need to re-add the indicesAccessControl in the context,
                // because the create index call doesn't do anything FLS or DLS
            }
        }

        grant(user, action, request);
    }

    private void setIndicesAccessControl(IndicesAccessControl accessControl) {
        if (threadContext.getTransient(INDICES_PERMISSIONS_KEY) == null) {
            threadContext.putTransient(INDICES_PERMISSIONS_KEY, accessControl);
        }
    }

    private void setOriginatingAction(String action) {
        String originatingAction = threadContext.getTransient(ORIGINATING_ACTION_KEY);
        if (originatingAction == null) {
            threadContext.putTransient(ORIGINATING_ACTION_KEY, action);
        }
    }

    private GlobalPermission permission(String[] roleNames) {
        if (roleNames.length == 0) {
            return GlobalPermission.NONE;
        }

        if (roleNames.length == 1) {
            Role role = rolesStore.role(roleNames[0]);
            return role == null ? GlobalPermission.NONE : role;
        }

        // we'll take all the roles and combine their associated permissions

        GlobalPermission.Compound.Builder roles = GlobalPermission.Compound.builder();
        for (String roleName : roleNames) {
            Role role = rolesStore.role(roleName);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles.build();
    }

    private Set<String> resolveIndices(User user, String action, TransportRequest request, ClusterState clusterState) {
        MetaData metaData = clusterState.metaData();
        for (IndicesAndAliasesResolver resolver : indicesAndAliasesResolvers) {
            if (resolver.requestType().isInstance(request)) {
                return resolver.resolve(user, action, request, metaData);
            }
        }
        assert false : "we should be able to resolve indices for any known request that requires indices privileges";
        throw denial(user, action, request);
    }

    private static boolean isScrollRelatedAction(String action) {
        return action.equals(SearchScrollAction.NAME) ||
                action.equals(SearchTransportService.FETCH_ID_SCROLL_ACTION_NAME) ||
                action.equals(SearchTransportService.QUERY_FETCH_SCROLL_ACTION_NAME) ||
                action.equals(SearchTransportService.QUERY_SCROLL_ACTION_NAME) ||
                action.equals(SearchTransportService.FREE_CONTEXT_SCROLL_ACTION_NAME) ||
                action.equals(ClearScrollAction.NAME) ||
                action.equals(SearchTransportService.CLEAR_SCROLL_CONTEXTS_ACTION_NAME);
    }

    private ElasticsearchSecurityException denial(User user, String action, TransportRequest request) {
        auditTrail.accessDenied(user, action, request);
        return denialException(user, action);
    }

    private ElasticsearchSecurityException denyRunAs(User user, String action, TransportRequest request) {
        auditTrail.runAsDenied(user, action, request);
        return denialException(user, action);
    }

    private void grant(User user, String action, TransportRequest request) {
        auditTrail.accessGranted(user, action, request);
    }

    private void grantRunAs(User user, String action, TransportRequest request) {
        auditTrail.runAsGranted(user, action, request);
    }

    private ElasticsearchSecurityException denialException(User user, String action) {
        // Special case for anonymous user
        if (anonymousService.isAnonymous(user)) {
            if (!anonymousService.authorizationExceptionsEnabled()) {
                throw authcFailureHandler.authenticationRequired(action);
            }
        }
        if (user.runAs() != null) {
            return authorizationError("action [{}] is unauthorized for user [{}] run as [{}]", action, user.principal(),
                    user.runAs().principal());
        }
        return authorizationError("action [{}] is unauthorized for user [{}]", action, user.principal());
    }
}
