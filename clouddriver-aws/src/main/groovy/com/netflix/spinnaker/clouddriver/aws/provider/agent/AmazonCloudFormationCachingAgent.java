/*
 * Copyright (c) 2019 Schibsted Media Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.aws.provider.agent;

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE;
import static com.netflix.spinnaker.clouddriver.aws.cache.Keys.Namespace.STACKS;

import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.cloudformation.model.Stack;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.*;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.DefaultCacheData;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.clouddriver.aws.AmazonCloudProvider;
import com.netflix.spinnaker.clouddriver.aws.cache.Keys;
import com.netflix.spinnaker.clouddriver.aws.provider.AwsInfrastructureProvider;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AmazonCloudFormationCachingAgent
    implements CachingAgent, OnDemandAgent, AccountAware, AgentIntervalAware {
  private final AmazonClientProvider amazonClientProvider;
  private final NetflixAmazonCredentials account;
  private final String region;
  private final OnDemandMetricsSupport metricsSupport;

  static final Set<AgentDataType> types =
      new HashSet<>(Collections.singletonList(AUTHORITATIVE.forType(STACKS.getNs())));

  public AmazonCloudFormationCachingAgent(
      AmazonClientProvider amazonClientProvider,
      NetflixAmazonCredentials account,
      String region,
      Registry registry) {
    this.amazonClientProvider = amazonClientProvider;
    this.account = account;
    this.region = region;
    this.metricsSupport =
        new OnDemandMetricsSupport(
            registry,
            this,
            String.format("%s:%s", AmazonCloudProvider.ID, OnDemandType.CloudFormation));
  }

  @Override
  public String getProviderName() {
    return AwsInfrastructureProvider.PROVIDER_NAME;
  }

  @Override
  public String getOnDemandAgentType() {
    return getAgentType();
  }

  @Override
  public OnDemandMetricsSupport getMetricsSupport() {
    return this.metricsSupport;
  }

  @Override
  public boolean handles(OnDemandType type, String cloudProvider) {
    return OnDemandType.CloudFormation.equals(type) && cloudProvider.equals(AmazonCloudProvider.ID);
  }

  @Override
  public OnDemandResult handle(ProviderCache providerCache, Map<String, ?> data) {
    if (shouldHandle(data)) {
      log.info(
          "Updating CloudFormation cache for account: {} and region: {}",
          account.getName(),
          this.region);
      DescribeStacksRequest describeStacksRequest =
          Optional.ofNullable((String) data.get("stackName"))
              .map(stackName -> new DescribeStacksRequest().withStackName(stackName))
              .orElse(new DescribeStacksRequest());
      return new OnDemandResult(
          getOnDemandAgentType(),
          queryStacks(providerCache, describeStacksRequest, true),
          Collections.emptyMap());
    } else {
      return null;
    }
  }

  private boolean shouldHandle(Map<String, ?> data) {
    String credentials = (String) data.get("credentials");
    List<String> region = (List<String>) data.get("region");
    return data.isEmpty()
        || (account.getName().equals(credentials)
            && region != null
            && region.contains(this.region));
  }

  @Override
  public Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    return Collections.emptyList();
  }

  @Override
  public String getAgentType() {
    return String.format(
        "%s/%s/%s",
        account.getName(), region, AmazonCloudFormationCachingAgent.class.getSimpleName());
  }

  @Override
  public String getAccountName() {
    return account.getName();
  }

  @Override
  public Collection<AgentDataType> getProvidedDataTypes() {
    return types;
  }

  @Override
  public CacheResult loadData(ProviderCache providerCache) {
    return queryStacks(providerCache, new DescribeStacksRequest(), false);
  }

  public CacheResult queryStacks(
      ProviderCache providerCache,
      DescribeStacksRequest describeStacksRequest,
      boolean isPartialResult) {
    log.info("Describing items in {}, partial result: {}", getAgentType(), isPartialResult);
    AmazonCloudFormation cloudformation =
        amazonClientProvider.getAmazonCloudFormation(account, region);

    ArrayList<CacheData> stackCacheData = new ArrayList<>();

    try {
      List<Stack> stacks = cloudformation.describeStacks(describeStacksRequest).getStacks();

      for (Stack stack : stacks) {
        Map<String, Object> stackAttributes = getStackAttributes(stack, cloudformation);
        String stackCacheKey =
            Keys.getCloudFormationKey(stack.getStackId(), region, account.getName());
        Map<String, Collection<String>> relationships = new HashMap<>();
        relationships.put(STACKS.getNs(), Collections.singletonList(stackCacheKey));
        stackCacheData.add(new DefaultCacheData(stackCacheKey, stackAttributes, relationships));
      }
    } catch (AmazonCloudFormationException e) {
      log.error("Error retrieving stacks", e);
    }

    log.info("Caching {} items in {}", stackCacheData.size(), getAgentType());
    HashMap<String, Collection<CacheData>> result = new HashMap<>();
    result.put(STACKS.getNs(), stackCacheData);
    return new DefaultCacheResult(result, isPartialResult);
  }

  private Map<String, Object> getStackAttributes(Stack stack, AmazonCloudFormation cloudformation) {
    Map<String, Object> stackAttributes = new HashMap<>();
    stackAttributes.put("stackId", stack.getStackId());
    stackAttributes.put(
        "tags", stack.getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    stackAttributes.put(
        "outputs",
        stack.getOutputs().stream()
            .collect(Collectors.toMap(Output::getOutputKey, Output::getOutputValue)));
    stackAttributes.put("stackName", stack.getStackName());
    stackAttributes.put("region", region);
    stackAttributes.put("accountName", account.getName());
    stackAttributes.put("accountId", account.getAccountId());
    stackAttributes.put("stackStatus", stack.getStackStatus());
    stackAttributes.put("creationTime", stack.getCreationTime());
    stackAttributes.put("changeSets", getChangeSets(stack, cloudformation));
    getStackStatusReason(stack, cloudformation)
        .map(statusReason -> stackAttributes.put("stackStatusReason", statusReason));
    return stackAttributes;
  }

  private List<Map<String, Object>> getChangeSets(
      Stack stack, AmazonCloudFormation cloudformation) {
    ListChangeSetsRequest listChangeSetsRequest =
        new ListChangeSetsRequest().withStackName(stack.getStackName());
    ListChangeSetsResult listChangeSetsResult =
        cloudformation.listChangeSets(listChangeSetsRequest);
    return listChangeSetsResult.getSummaries().stream()
        .map(
            summary -> {
              Map<String, Object> changeSetAttributes = new HashMap<>();
              changeSetAttributes.put("name", summary.getChangeSetName());
              changeSetAttributes.put("status", summary.getStatus());
              changeSetAttributes.put("statusReason", summary.getStatusReason());
              DescribeChangeSetRequest describeChangeSetRequest =
                  new DescribeChangeSetRequest()
                      .withChangeSetName(summary.getChangeSetName())
                      .withStackName(stack.getStackName());
              DescribeChangeSetResult describeChangeSetResult =
                  cloudformation.describeChangeSet(describeChangeSetRequest);
              changeSetAttributes.put("changes", describeChangeSetResult.getChanges());
              log.debug(
                  "Adding change set attributes for stack {}: {}",
                  stack.getStackName(),
                  changeSetAttributes);
              return changeSetAttributes;
            })
        .collect(Collectors.toList());
  }

  private Optional<String> getStackStatusReason(Stack stack, AmazonCloudFormation cloudformation) {
    if (stack.getStackStatus().endsWith("ROLLBACK_COMPLETE")) {
      DescribeStackEventsRequest request =
          new DescribeStackEventsRequest().withStackName(stack.getStackName());
      return cloudformation.describeStackEvents(request).getStackEvents().stream()
          .filter(e -> e.getResourceStatus().endsWith("FAILED"))
          .findFirst()
          .map(StackEvent::getResourceStatusReason);
    } else {
      return Optional.empty();
    }
  }

  @Override
  public Long getAgentInterval() {
    return 60000L;
  }
}
