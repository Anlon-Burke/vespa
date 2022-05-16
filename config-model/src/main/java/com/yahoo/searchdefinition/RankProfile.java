// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition;

import ai.vespa.rankingexpression.importer.configmodelview.ImportedMlModels;
import com.google.common.collect.ImmutableMap;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.path.Path;
import com.yahoo.search.query.profile.QueryProfileRegistry;
import com.yahoo.search.query.profile.types.FieldDescription;
import com.yahoo.search.query.profile.types.QueryProfileType;
import com.yahoo.search.query.ranking.Diversity;
import com.yahoo.searchdefinition.document.Attribute;
import com.yahoo.searchdefinition.document.ImmutableSDField;
import com.yahoo.searchdefinition.document.SDDocumentType;
import com.yahoo.searchdefinition.expressiontransforms.ExpressionTransforms;
import com.yahoo.searchdefinition.expressiontransforms.RankProfileTransformContext;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.FeatureList;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.rule.Arguments;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a rank profile - a named set of ranking settings
 *
 * @author bratseth
 */
public class RankProfile implements Cloneable {

    public final static String FIRST_PHASE = "firstphase";
    public final static String SECOND_PHASE = "secondphase";

    /** The schema-unique name of this rank profile */
    private final String name;

    /** The schema owning this profile, or null if global (owned by a model) */
    private final ImmutableSchema schema;

    private final List<String> inheritedNames = new ArrayList<>();

    /** The resolved inherited profiles, or null when not resolved. */
    private List<RankProfile> inherited;

    private MatchPhaseSettings matchPhaseSettings = null;

    protected Set<RankSetting> rankSettings = new java.util.LinkedHashSet<>();

    /** The ranking expression to be used for first phase */
    private RankingExpressionFunction firstPhaseRanking = null;

    /** The ranking expression to be used for second phase */
    private RankingExpressionFunction secondPhaseRanking = null;

    /** Number of hits to be reranked in second phase, -1 means use default */
    private int rerankCount = -1;

    /** Mysterious attribute */
    private int keepRankCount = -1;

    private int numThreadsPerSearch = -1;
    private int minHitsPerThread = -1;
    private int numSearchPartitions = -1;

    private Double termwiseLimit = null;
    private Double postFilterThreshold = null;
    private Double approximateThreshold = null;

    /** The drop limit used to drop hits with rank score less than or equal to this value */
    private double rankScoreDropLimit = -Double.MAX_VALUE;

    private Set<ReferenceNode> summaryFeatures;
    private String inheritedSummaryFeaturesProfileName;

    private Set<ReferenceNode> matchFeatures;
    private String inheritedMatchFeaturesProfileName;

    private Set<ReferenceNode> rankFeatures;

    /** The properties of this - a multimap */
    private Map<String, List<RankProperty>> rankProperties = new LinkedHashMap<>();

    private Boolean ignoreDefaultRankFeatures = null;

    private Map<String, RankingExpressionFunction> functions = new LinkedHashMap<>();
    // This cache must be invalidated every time modifications are done to 'functions'.
    private CachedFunctions allFunctionsCached = null;

    private Map<Reference, Input> inputs = new LinkedHashMap<>();

    private Map<Reference, Constant> constants = new LinkedHashMap<>();

    private Map<String, OnnxModel> onnxModels = new LinkedHashMap<>();

    private Set<String> filterFields = new HashSet<>();

    private final RankProfileRegistry rankProfileRegistry;

    private final TypeSettings attributeTypes = new TypeSettings();

    private List<ImmutableSDField> allFieldsList;

    private Boolean strict;

    private final ApplicationPackage applicationPackage;
    private final DeployLogger deployLogger;

    /**
     * Creates a new rank profile for a particular schema
     *
     * @param name                the name of the new profile
     * @param schema              the schema owning this profile
     * @param rankProfileRegistry the {@link com.yahoo.searchdefinition.RankProfileRegistry} to use for storing
     *                            and looking up rank profiles.
     */
    public RankProfile(String name, Schema schema, RankProfileRegistry rankProfileRegistry) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.schema = Objects.requireNonNull(schema, "schema cannot be null");
        this.rankProfileRegistry = rankProfileRegistry;
        this.applicationPackage = schema.applicationPackage();
        this.deployLogger = schema.getDeployLogger();
    }

    /**
     * Creates a global rank profile
     *
     * @param name  the name of the new profile
     */
    public RankProfile(String name, ApplicationPackage applicationPackage, DeployLogger deployLogger,
                       RankProfileRegistry rankProfileRegistry) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.schema = null;
        this.rankProfileRegistry = rankProfileRegistry;
        this.applicationPackage = applicationPackage;
        this.deployLogger = deployLogger;
    }

    public String name() { return name; }

    /** Returns the search definition owning this, or null if it is global */
    public ImmutableSchema schema() { return schema; }

    /** Returns the application this is part of */
    public ApplicationPackage applicationPackage() {
        return applicationPackage;
    }

    private Stream<ImmutableSDField> allFields() {
        if (schema == null) return Stream.empty();
        if (allFieldsList == null) {
            allFieldsList = schema.allFieldsList();
        }
        return allFieldsList.stream();
    }

    private Stream<ImmutableSDField> allImportedFields() {
        return schema != null ? schema.allImportedFields() : Stream.empty();
    }

    /**
     * Returns whether type checking should fail if this profile accesses query features that are
     * not defined in query profile types.
     *
     * Default is false.
     */
    public boolean isStrict() {
        Boolean declaredStrict = declaredStrict();
        if (declaredStrict != null) return declaredStrict;
        return false;
    }

    /** Returns the strict value declared in this or any parent profile. */
    public Boolean declaredStrict() {
        if (strict != null) return strict;
        return uniquelyInherited(p -> p.declaredStrict(), "strict").orElse(null);
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }

    /**
     * Adds a profile to those inherited by this.
     * The profile must belong to this schema (directly or by inheritance).
     */
    public void inherit(String inheritedName) {
        inherited = null;
        inheritedNames.add(inheritedName);
    }

    /** Returns the names of the profiles this inherits, if any. */
    public List<String> inheritedNames() { return Collections.unmodifiableList(inheritedNames); }

    /** Returns the rank profiles inherited by this. */
    private List<RankProfile> inherited() {
        if (inheritedNames.isEmpty()) return List.of();
        if (inherited != null) return inherited;

        inherited = resolveInheritedProfiles(schema);
        List<String> children = new ArrayList<>();
        children.add(createFullyQualifiedName());
        inherited.forEach(profile -> verifyNoInheritanceCycle(children, profile));
        return inherited;
    }

    private String createFullyQualifiedName() {
        return (schema != null)
                ? (schema.getName() + "." + name())
                : name();
    }

    private void verifyNoInheritanceCycle(List<String> children, RankProfile parent) {
        children.add(parent.createFullyQualifiedName());
        String root = children.get(0);
        if (root.equals(parent.createFullyQualifiedName()))
            throw new IllegalArgumentException("There is a cycle in the inheritance for rank-profile '" + root + "' = " + children);
        for (RankProfile parentInherited : parent.inherited())
            verifyNoInheritanceCycle(children, parentInherited);
    }

    private List<RankProfile> resolveInheritedProfiles(ImmutableSchema schema) {
        List<RankProfile> inherited = new ArrayList<>();
        for (String inheritedName : inheritedNames) {
            RankProfile inheritedProfile = schema ==  null
                                           ? rankProfileRegistry.getGlobal(inheritedName)
                                           : resolveInheritedProfile(schema, inheritedName);
            if (inheritedProfile == null)
                throw new IllegalArgumentException("rank-profile '" + name() + "' inherits '" + inheritedName +
                                                   "', but this is not found in " +
                                                   ((schema() != null) ? schema() : " global rank profiles"));
            inherited.add(inheritedProfile);
        }
        return inherited;
    }

    private RankProfile resolveInheritedProfile(ImmutableSchema schema, String inheritedName) {
        SDDocumentType documentType = schema.getDocument();
        if (documentType != null) {
            if (name.equals(inheritedName)) {
                // If you seemingly inherit yourself, you are actually referencing a rank-profile in one of your inherited schemas
                for (SDDocumentType baseType : documentType.getInheritedTypes()) {
                    RankProfile resolvedFromBase = rankProfileRegistry.resolve(baseType, inheritedName);
                    if (resolvedFromBase != null) return resolvedFromBase;
                }
            }
            return rankProfileRegistry.resolve(documentType, inheritedName);
        }
        return rankProfileRegistry.get(schema.getName(), inheritedName);
    }

    /** Returns whether this profile inherits (directly or indirectly) the given profile name. */
    public boolean inherits(String name) {
        for (RankProfile inheritedProfile : inherited()) {
            if (inheritedProfile.name().equals(name)) return true;
            if (inheritedProfile.inherits(name)) return true;
        }
        return false;
    }

    public void setMatchPhaseSettings(MatchPhaseSettings settings) {
        settings.checkValid();
        this.matchPhaseSettings = settings;
    }

    public MatchPhaseSettings getMatchPhaseSettings() {
        if (matchPhaseSettings != null) return matchPhaseSettings;
        return uniquelyInherited(p -> p.getMatchPhaseSettings(), "match phase settings").orElse(null);
    }

    /** Returns the uniquely determined property, where non-empty is defined as non-null */
    private <T> Optional<T> uniquelyInherited(Function<RankProfile, T> propertyRetriever,
                                              String propertyDescription) {
        return uniquelyInherited(propertyRetriever, p -> p != null, propertyDescription);
    }

    /**
     * Returns the property retrieved by the given function, if it is only present in a single unique variant
     * among all profiled inherited by this, or empty if not present.
     * Note that for properties that don't implement a values-based equals this reverts to the stricter condition that
     * only one inherited profile can define a non-empty value at all.
     *
     * @throws IllegalArgumentException if the inherited profiles defines multiple different values of the property
     */
    private <T> Optional<T> uniquelyInherited(Function<RankProfile, T> propertyRetriever,
                                              Predicate<T> nonEmptyValueFilter,
                                              String propertyDescription) {
        Set<T> uniqueProperties = inherited().stream()
                                             .map(p -> propertyRetriever.apply(p))
                                             .filter(p -> nonEmptyValueFilter.test(p))
                                             .collect(Collectors.toSet());
        if (uniqueProperties.isEmpty()) return Optional.empty();
        if (uniqueProperties.size() == 1) return Optional.of(uniqueProperties.stream().findAny().get());
        throw new IllegalArgumentException("Only one of the profiles inherited by " + this + " can contain " +
                                           propertyDescription + ", but it is present in multiple");
    }

    public void addRankSetting(RankSetting rankSetting) {
        rankSettings.add(rankSetting);
    }

    public void addRankSetting(String fieldName, RankSetting.Type type, Object value) {
        addRankSetting(new RankSetting(fieldName, type, value));
    }

    /**
     * Returns the a rank setting of a field, or null if there is no such rank setting in this profile
     *
     * @param field the field whose settings to return
     * @param type  the type that the field is required to be
     * @return the rank setting found, or null
     */
    RankSetting getDeclaredRankSetting(String field, RankSetting.Type type) {
        for (Iterator<RankSetting> i = declaredRankSettingIterator(); i.hasNext(); ) {
            RankSetting setting = i.next();
            if (setting.getFieldName().equals(field) && setting.getType() == type) {
                return setting;
            }
        }
        return null;
    }

    /**
     * Returns a rank setting of field or index, or null if there is no such rank setting in this profile or one it
     * inherits
     *
     * @param field the field whose settings to return
     * @param type  the type that the field is required to be
     * @return the rank setting found, or null
     */
    public RankSetting getRankSetting(String field, RankSetting.Type type) {
        RankSetting rankSetting = getDeclaredRankSetting(field, type);
        if (rankSetting != null) return rankSetting;

        return uniquelyInherited(p -> p.getRankSetting(field, type), "rank setting " + type + " on " + field).orElse(null);
    }

    /**
     * Returns the rank settings in this rank profile
     *
     * @return an iterator for the declared rank setting
     */
    public Iterator<RankSetting> declaredRankSettingIterator() {
        return Collections.unmodifiableSet(rankSettings).iterator();
    }

    /**
     * Returns all settings in this profile or any profile it inherits
     *
     * @return an iterator for all rank settings of this
     */
    public Iterator<RankSetting> rankSettingIterator() {
        return rankSettings().iterator();
    }

    /**
     * Returns a snapshot of the rank settings of this and everything it inherits.
     * Changes to the returned set will not be reflected in this rank profile.
     */
    public Set<RankSetting> rankSettings() {
        Set<RankSetting> settings = new LinkedHashSet<>();
        for (RankProfile inheritedProfile : inherited()) {
            for (RankSetting setting : inheritedProfile.rankSettings()) {
                if (settings.contains(setting))
                    throw new IllegalArgumentException(setting + " is present in " + inheritedProfile + " inherited by " +
                                                       this + ", but is also present in another profile inherited by it");
                settings.add(setting);
            }
        }

        // TODO: Here we do things in the wrong order to not break tests. Reverse this.
        Set<RankSetting> finalSettings = new LinkedHashSet<>(rankSettings);
        finalSettings.addAll(settings);
        return finalSettings;
    }

    public void add(Constant constant) {
        constants.put(constant.name(), constant);
    }

    /** Returns an unmodifiable view of the constants declared in this */
    public Map<Reference, Constant> declaredConstants() { return Collections.unmodifiableMap(constants); }

    /** Returns an unmodifiable view of the constants available in this */
    public Map<Reference, Constant> constants() {
        Map<Reference, Constant> allConstants = new HashMap<>();
        for (var inheritedProfile : inherited()) {
            for (var constant : inheritedProfile.constants().values()) {
                if (allConstants.containsKey(constant.name()))
                    throw new IllegalArgumentException(constant + "' is present in " +
                                                       inheritedProfile + " inherited by " +
                                                       this + ", but is also present in another profile inherited by it");
                allConstants.put(constant.name(), constant);
            }
        }

        if (schema != null)
            allConstants.putAll(schema.constants());
        allConstants.putAll(constants);
        return allConstants;
    }

    public void add(OnnxModel model) {
        onnxModels.put(model.getName(), model);
    }

    /** Returns an unmodifiable map of the onnx models declared in this. */
    public Map<String, OnnxModel> declaredOnnxModels() { return onnxModels; }

    /** Returns an unmodifiable map of the onnx models available in this. */
    public Map<String, OnnxModel> onnxModels() {
        Map<String, OnnxModel> allModels = new HashMap<>();
        for (var inheritedProfile : inherited()) {
            for (var model : inheritedProfile.onnxModels().values()) {
                if (allModels.containsKey(model.getName()))
                    throw new IllegalArgumentException(model + "' is present in " +
                                                       inheritedProfile + " inherited by " +
                                                       this + ", but is also present in another profile inherited by it");
                allModels.put(model.getName(), model);
            }
        }

        if (schema != null)
            allModels.putAll(schema.onnxModels());
        allModels.putAll(onnxModels);
        return allModels;
    }

    public void addAttributeType(String attributeName, String attributeType) {
        attributeTypes.addType(attributeName, attributeType);
    }

    public Map<String, String> getAttributeTypes() {
        return attributeTypes.getTypes();
    }

    /**
     * Returns the ranking expression to use by this. This expression must not be edited.
     * Returns null if no expression is set.
     */
    public RankingExpression getFirstPhaseRanking() {
        RankingExpressionFunction function = getFirstPhase();
        if (function == null) return null;
        return function.function.getBody();
    }

    public RankingExpressionFunction getFirstPhase() {
        if (firstPhaseRanking != null) return firstPhaseRanking;
        return uniquelyInherited(p -> p.getFirstPhase(), "first-phase expression").orElse(null);
    }

    void setFirstPhaseRanking(RankingExpression rankingExpression) {
        this.firstPhaseRanking = new RankingExpressionFunction(new ExpressionFunction(FIRST_PHASE, Collections.emptyList(), rankingExpression), false);
    }

    public void setFirstPhaseRanking(String expression) {
        try {
            firstPhaseRanking = new RankingExpressionFunction(parseRankingExpression(FIRST_PHASE, Collections.emptyList(), expression), false);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Illegal first phase ranking function", e);
        }
    }

    /**
     * Returns the ranking expression to use by this. This expression must not be edited.
     * Returns null if no expression is set.
     */
    public RankingExpression getSecondPhaseRanking() {
        RankingExpressionFunction function = getSecondPhase();
        if (function == null) return null;
        return function.function().getBody();
    }

    public RankingExpressionFunction getSecondPhase() {
        if (secondPhaseRanking != null) return secondPhaseRanking;
        return uniquelyInherited(p -> p.getSecondPhase(), "second-phase expression").orElse(null);
    }

    public void setSecondPhaseRanking(String expression) {
        try {
            secondPhaseRanking = new RankingExpressionFunction(parseRankingExpression(SECOND_PHASE, Collections.emptyList(), expression), false);
        }
        catch (ParseException e) {
            throw new IllegalArgumentException("Illegal second phase ranking function", e);
        }
    }

    // TODO: Below we have duplicate methods for summary and match features: Encapsulate this in a single parametrized
    //       class instead (and probably make rank features work the same).

    /**
     * Sets the name this should inherit the summary features of.
     * Without setting this, this will either have the summary features of the single parent setting them,
     * or if summary features are set in this, only have the summary features in this.
     * With this set the resulting summary features of this will be the superset of those defined in this and
     * the final (with inheritance included) summary features of the given parent.
     * The profile must be one which is directly inherited by this.
     */
    public void setInheritedSummaryFeatures(String parentProfile) {
        if ( ! inheritedNames().contains(parentProfile))
            throw new IllegalArgumentException("This can only inherit the summary features of a directly inherited profile, '" +
                                               ", but attempting to inherit '" + parentProfile);
        this.inheritedSummaryFeaturesProfileName = parentProfile;
    }

    /**
     * Sets the name of a profile this should inherit the match features of.
     * Without setting this, this will either have the match features of the single parent setting them,
     * or if match features are set in this, only have the match features in this.
     * With this set the resulting match features of this will be the superset of those defined in this and
     * the final (with inheritance included) match features of the given parent.
     * The profile must be one which which is directly inherited by this.
     *
     */
    public void setInheritedMatchFeatures(String parentProfile) {
        if ( ! inheritedNames().contains(parentProfile))
            throw new IllegalArgumentException("This can only inherit the match features of a directly inherited profile, '" +
                                               ", but attempting to inherit '" + parentProfile);
        this.inheritedMatchFeaturesProfileName = parentProfile;
    }

    /** Returns a read-only view of the summary features to use in this profile. This is never null */
    public Set<ReferenceNode> getSummaryFeatures() {
        if (inheritedSummaryFeaturesProfileName != null && summaryFeatures != null) {
            Set<ReferenceNode> combined = new HashSet<>();
            RankProfile inherited = inherited().stream()
                                               .filter(p -> p.name().equals(inheritedSummaryFeaturesProfileName))
                                               .findAny()
                                               .orElseThrow();
            combined.addAll(inherited.getSummaryFeatures());
            combined.addAll(summaryFeatures);
            return Collections.unmodifiableSet(combined);
        }
        if (summaryFeatures != null) return Collections.unmodifiableSet(summaryFeatures);
        return uniquelyInherited(p -> p.getSummaryFeatures(), f -> ! f.isEmpty(), "summary features")
                .orElse(Set.of());
    }

    /** Returns a read-only view of the match features to use in this profile. This is never null */
    public Set<ReferenceNode> getMatchFeatures() {
        if (inheritedMatchFeaturesProfileName != null && matchFeatures != null) {
            Set<ReferenceNode> combined = new HashSet<>();
            RankProfile inherited = inherited().stream()
                                               .filter(p -> p.name().equals(inheritedMatchFeaturesProfileName))
                                               .findAny()
                                               .orElseThrow();
            combined.addAll(inherited.getMatchFeatures());
            combined.addAll(matchFeatures);
            return Collections.unmodifiableSet(combined);
        }
        if (matchFeatures != null) return Collections.unmodifiableSet(matchFeatures);
        return uniquelyInherited(p -> p.getMatchFeatures(), f -> ! f.isEmpty(), "match features")
                .orElse(Set.of());
    }

    private void addSummaryFeature(ReferenceNode feature) {
        if (summaryFeatures == null)
            summaryFeatures = new LinkedHashSet<>();
        summaryFeatures.add(feature);
    }

    private void addMatchFeature(ReferenceNode feature) {
        if (matchFeatures == null)
            matchFeatures = new LinkedHashSet<>();
        matchFeatures.add(feature);
    }

    /** Adds the content of the given feature list to the internal list of summary features. */
    public void addSummaryFeatures(FeatureList features) {
        for (ReferenceNode feature : features) {
            addSummaryFeature(feature);
        }
    }

    /** Adds the content of the given feature list to the internal list of match features. */
    public void addMatchFeatures(FeatureList features) {
        for (ReferenceNode feature : features) {
            addMatchFeature(feature);
        }
    }

    /** Returns a read-only view of the rank features to use in this profile. This is never null */
    public Set<ReferenceNode> getRankFeatures() {
        if (rankFeatures != null) return Collections.unmodifiableSet(rankFeatures);
        return uniquelyInherited(p -> p.getRankFeatures(), f -> ! f.isEmpty(), "summary-features")
                .orElse(Set.of());
    }

    private void addRankFeature(ReferenceNode feature) {
        if (rankFeatures == null)
            rankFeatures = new LinkedHashSet<>();
        rankFeatures.add(feature);
    }

    /**
     * Adds the content of the given feature list to the internal list of rank features.
     *
     * @param features The features to add.
     */
    public void addRankFeatures(FeatureList features) {
        for (ReferenceNode feature : features) {
            addRankFeature(feature);
        }
    }

    /** Returns a read only flattened list view of the rank properties to use in this profile. This is never null. */
    public List<RankProperty> getRankProperties() {
        List<RankProperty> properties = new ArrayList<>();
        for (List<RankProperty> propertyList : getRankPropertyMap().values()) {
            properties.addAll(propertyList);
        }
        return Collections.unmodifiableList(properties);
    }

    /** Returns a read only map view of the rank properties to use in this profile. This is never null. */
    public Map<String, List<RankProperty>> getRankPropertyMap() {
        if (rankProperties.size() == 0 && inherited().isEmpty()) return Map.of();
        if (inherited().isEmpty()) return Collections.unmodifiableMap(rankProperties);

        var inheritedProperties = uniquelyInherited(p -> p.getRankPropertyMap(), m -> ! m.isEmpty(), "rank-properties")
                .orElse(Map.of());
        if (rankProperties.isEmpty()) return inheritedProperties;

        // Neither is null
        Map<String, List<RankProperty>> combined = new LinkedHashMap<>(inheritedProperties);
        combined.putAll(rankProperties); // Don't combine values across inherited properties
        return Collections.unmodifiableMap(combined);
    }

    public void addRankProperty(String name, String parameter) {
        addRankProperty(new RankProperty(name, parameter));
    }

    private void addRankProperty(RankProperty rankProperty) {
        // Just the usual multimap semantics here
        rankProperties.computeIfAbsent(rankProperty.getName(), (String key) -> new ArrayList<>(1)).add(rankProperty);
    }

    public void setRerankCount(int rerankCount) { this.rerankCount = rerankCount; }

    public int getRerankCount() {
        if (rerankCount >= 0) return rerankCount;
        return uniquelyInherited(p -> p.getRerankCount(), c -> c >= 0, "rerank-count").orElse(-1);
    }

    public void setNumThreadsPerSearch(int numThreads) { this.numThreadsPerSearch = numThreads; }

    public int getNumThreadsPerSearch() {
        if (numThreadsPerSearch >= 0) return numThreadsPerSearch;
        return uniquelyInherited(p -> p.getNumThreadsPerSearch(), n -> n >= 0, "num-threads-per-search")
                .orElse(-1);
    }

    public void setMinHitsPerThread(int minHits) { this.minHitsPerThread = minHits; }

    public int getMinHitsPerThread() {
        if (minHitsPerThread >= 0) return minHitsPerThread;
        return uniquelyInherited(p -> p.getMinHitsPerThread(), n -> n >= 0, "min-hits-per-search").orElse(-1);
    }

    public void setNumSearchPartitions(int numSearchPartitions) { this.numSearchPartitions = numSearchPartitions; }

    public int getNumSearchPartitions() {
        if (numSearchPartitions >= 0) return numSearchPartitions;
        return uniquelyInherited(p -> p.getNumSearchPartitions(), n -> n >= 0, "num-search-partitions").orElse(-1);
    }

    public void setTermwiseLimit(double termwiseLimit) { this.termwiseLimit = termwiseLimit; }
    public void setPostFilterThreshold(double threshold) { this.postFilterThreshold = threshold; }
    public void setApproximateThreshold(double threshold) { this.approximateThreshold = threshold; }

    public OptionalDouble getTermwiseLimit() {
        if (termwiseLimit != null) return OptionalDouble.of(termwiseLimit);
        return uniquelyInherited(p -> p.getTermwiseLimit(), l -> l.isPresent(), "termwise-limit")
                .orElse(OptionalDouble.empty());
    }

    public OptionalDouble getPostFilterThreshold() {
        if (postFilterThreshold != null) {
            return OptionalDouble.of(postFilterThreshold);
        }
        return uniquelyInherited(p -> p.getPostFilterThreshold(), l -> l.isPresent(), "post-filter-threshold").orElse(OptionalDouble.empty());
    }

    public OptionalDouble getApproximateThreshold() {
        if (approximateThreshold != null) {
            return OptionalDouble.of(approximateThreshold);
        }
        return uniquelyInherited(p -> p.getApproximateThreshold(), l -> l.isPresent(), "approximate-threshold").orElse(OptionalDouble.empty());
    }

    /** Whether we should ignore the default rank features. Set to null to use inherited */
    public void setIgnoreDefaultRankFeatures(Boolean ignoreDefaultRankFeatures) {
        this.ignoreDefaultRankFeatures = ignoreDefaultRankFeatures;
    }

    public Boolean getIgnoreDefaultRankFeatures() {
        if (ignoreDefaultRankFeatures != null) return ignoreDefaultRankFeatures;
        return uniquelyInherited(p -> p.getIgnoreDefaultRankFeatures(), "ignore-default-rank-features").orElse(false);
    }

    public void setKeepRankCount(int rerankArraySize) { this.keepRankCount = rerankArraySize; }

    public int getKeepRankCount() {
        if (keepRankCount >= 0) return keepRankCount;
        return uniquelyInherited(p -> p.getKeepRankCount(), c -> c >= 0, "keep-rank-count").orElse(-1);
    }

    public void setRankScoreDropLimit(double rankScoreDropLimit) { this.rankScoreDropLimit = rankScoreDropLimit; }

    public double getRankScoreDropLimit() {
        if (rankScoreDropLimit > -Double.MAX_VALUE) return rankScoreDropLimit;
        return uniquelyInherited(p -> p.getRankScoreDropLimit(), c -> c > -Double.MAX_VALUE, "rank.score-drop-limit")
                .orElse(rankScoreDropLimit);
    }

    public void addFunction(String name, List<String> arguments, String expression, boolean inline) {
        try {
            addFunction(parseRankingExpression(name, arguments, expression), inline);
        }
        catch (ParseException e) {
            throw new IllegalArgumentException("Could not parse function '" + name + "'", e);
        }
    }

    /** Adds a function and returns it */
    public RankingExpressionFunction addFunction(ExpressionFunction function, boolean inline) {
        RankingExpressionFunction rankingExpressionFunction = new RankingExpressionFunction(function, inline);
        if (functions.containsKey(function.getName())) {
            deployLogger.log(Level.WARNING, "Function '" + function.getName() + "' is defined twice " +
                    "in rank profile '" + this.name + "'");
        }
        functions.put(function.getName(), rankingExpressionFunction);
        allFunctionsCached = null;
        return rankingExpressionFunction;
    }

    /**
     * Adds the type of an input feature consumed by this profile.
     * All inputs must either be declared through this or in query profile types,
     * otherwise they are assumes to be scalars.
     */
    public void addInput(Reference reference, Input input) {
        if (inputs.containsKey(reference)) {
            Input existing = inputs().get(reference);
            if (! input.equals(existing))
                throw new IllegalArgumentException("Duplicate input: Has both " + input + " and existing");
        }
        inputs.put(reference, input);
    }

    /** Returns the inputs of this, which also includes all inputs of the parents of this. */
    // This is less restrictive than most other constructs in allowing inputs to be defined in all parent profiles
    // because inputs are tied closer to functions than the profile itself.
    public Map<Reference, Input> inputs() {
        if (inputs.isEmpty() && inherited().isEmpty()) return Map.of();
        if (inherited().isEmpty()) return Collections.unmodifiableMap(inputs);

        // Combine
        Map<Reference, Input> allInputs = new LinkedHashMap<>();
        for (var inheritedProfile : inherited()) {
            for (var input : inheritedProfile.inputs().entrySet()) {
                Input existing = allInputs.get(input.getKey());
                if (existing != null && ! existing.equals(input.getValue()))
                    throw new IllegalArgumentException(this + " inherits " + inheritedProfile + " which contains " +
                                                       input.getValue() + ", but this input is already defined as " +
                                                       existing + " in another profile this inherits");
                allInputs.put(input.getKey(), input.getValue());
            }
        }
        allInputs.putAll(inputs);
        return Collections.unmodifiableMap(allInputs);
    }

    public static class MutateOperation {
        public enum Phase { on_match, on_first_phase, on_second_phase, on_summary}
        final Phase phase;
        final String attribute;
        final String operation;
        public MutateOperation(Phase phase, String attribute, String operation) {
            this.phase = phase;
            this.attribute = attribute;
            this.operation = operation;
        }
    }
    private final List<MutateOperation> mutateOperations = new ArrayList<>();

    public void addMutateOperation(MutateOperation op) {
        mutateOperations.add(op);
        String prefix = "vespa.mutate." + op.phase.toString();
        addRankProperty(prefix + ".attribute", op.attribute);
        addRankProperty(prefix + ".operation", op.operation);
    }
    public void addMutateOperation(MutateOperation.Phase phase, String attribute, String operation) {
        addMutateOperation(new MutateOperation(phase, attribute, operation));
    }
    public List<MutateOperation> getMutateOperations() { return mutateOperations; }

    public RankingExpressionFunction findFunction(String name) {
        RankingExpressionFunction function = functions.get(name);
        if (function != null) return function;
        return uniquelyInherited(p -> p.findFunction(name), "function '" + name + "'").orElse(null);
    }

    /** Returns an unmodifiable snapshot of the functions in this */
    public Map<String, RankingExpressionFunction> getFunctions() {
        updateCachedFunctions();
        return allFunctionsCached.allRankingExpressionFunctions;
    }
    private ImmutableMap<String, ExpressionFunction> getExpressionFunctions() {
        updateCachedFunctions();
        return allFunctionsCached.allExpressionFunctions;
    }
    private void updateCachedFunctions() {
        if (needToUpdateFunctionCache()) {
            allFunctionsCached = new CachedFunctions(gatherAllFunctions());
        }
    }

    private  Map<String, RankingExpressionFunction> gatherAllFunctions() {
        if (functions.isEmpty() && inherited().isEmpty()) return Map.of();
        if (inherited().isEmpty()) return Collections.unmodifiableMap(new LinkedHashMap<>(functions));

        // Combine
        Map<String, RankingExpressionFunction> allFunctions = new LinkedHashMap<>();
        for (var inheritedProfile : inherited()) {
            for (var function : inheritedProfile.getFunctions().entrySet()) {
                if (allFunctions.containsKey(function.getKey()))
                    throw new IllegalArgumentException(this + " inherits " + inheritedProfile + " which contains " +
                                                       function.getValue() + ", but this function is already " +
                                                       "defined in another profile this inherits");
                allFunctions.put(function.getKey(), function.getValue());
            }
        }
        allFunctions.putAll(functions);
        return Collections.unmodifiableMap(allFunctions);
    }

    private boolean needToUpdateFunctionCache() {
        if (inherited().stream().anyMatch(profile -> profile.needToUpdateFunctionCache())) return true;
        return allFunctionsCached == null;
    }

    public Set<String> filterFields() { return filterFields; }

    /** Returns all filter fields in this profile and any profile it inherits. */
    public Set<String> allFilterFields() {
        Set<String> inheritedFilterFields = uniquelyInherited(p -> p.allFilterFields(), fields -> ! fields.isEmpty(),
                                                              "filter fields").orElse(Set.of());

        if (inheritedFilterFields.isEmpty()) return Collections.unmodifiableSet(filterFields);

        Set<String> combined = new LinkedHashSet<>(inheritedFilterFields);
        combined.addAll(filterFields());
        return combined;
    }

    private ExpressionFunction parseRankingExpression(String name, List<String> arguments, String expression) throws ParseException {
        if (expression.trim().length() == 0)
            throw new ParseException("Encountered an empty ranking expression in " + name() + ", " + name + ".");

        try (Reader rankingExpressionReader = openRankingExpressionReader(name, expression.trim())) {
            return new ExpressionFunction(name, arguments, new RankingExpression(name, rankingExpressionReader));
        }
        catch (com.yahoo.searchlib.rankingexpression.parser.ParseException e) {
            ParseException exception = new ParseException("Could not parse ranking expression '" + expression.trim() +
                                                          "' in " + name() + ", " + name + ".");
            throw (ParseException)exception.initCause(e);
        }
        catch (IOException e) {
            throw new RuntimeException("IOException parsing ranking expression '" + name + "'", e);
        }
    }

    private static String extractFileName(String expression) {
        String fileName = expression.substring("file:".length()).trim();
        if ( ! fileName.endsWith(ApplicationPackage.RANKEXPRESSION_NAME_SUFFIX))
            fileName = fileName + ApplicationPackage.RANKEXPRESSION_NAME_SUFFIX;

        return fileName;
    }

    private Reader openRankingExpressionReader(String expName, String expression) {
        if (!expression.startsWith("file:")) return new StringReader(expression);

        String fileName = extractFileName(expression);
        Path.fromString(fileName); // No ".."
        if (fileName.contains("/")) // See ticket 4102122
            throw new IllegalArgumentException("In " + name() + ", " + expName + ", ranking references file '" +
                                               fileName + "' in a different directory, which is not supported.");

        return schema.getRankingExpression(fileName);
    }

    /** Shallow clones this */
    @Override
    public RankProfile clone() {
        try {
            RankProfile clone = (RankProfile)super.clone();
            clone.rankSettings = new LinkedHashSet<>(this.rankSettings);
            clone.matchPhaseSettings = this.matchPhaseSettings; // hmm?
            clone.summaryFeatures = summaryFeatures != null ? new LinkedHashSet<>(this.summaryFeatures) : null;
            clone.matchFeatures = matchFeatures != null ? new LinkedHashSet<>(this.matchFeatures) : null;
            clone.rankFeatures = rankFeatures != null ? new LinkedHashSet<>(this.rankFeatures) : null;
            clone.rankProperties = new LinkedHashMap<>(this.rankProperties);
            clone.inputs = new LinkedHashMap<>(this.inputs);
            clone.functions = new LinkedHashMap<>(this.functions);
            clone.allFunctionsCached = null;
            clone.filterFields = new HashSet<>(this.filterFields);
            clone.constants = new HashMap<>(this.constants);
            return clone;
        }
        catch (CloneNotSupportedException e) {
            throw new RuntimeException("Won't happen", e);
        }
    }

    /**
     * Returns a copy of this where the content is optimized for execution.
     * Compiled profiles should never be modified.
     */
    public RankProfile compile(QueryProfileRegistry queryProfiles, ImportedMlModels importedModels) {
        try {
            RankProfile compiled = this.clone();
            compiled.compileThis(queryProfiles, importedModels);
            return compiled;
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rank profile '" + name() + "' is invalid", e);
        }
    }

    private void compileThis(QueryProfileRegistry queryProfiles, ImportedMlModels importedModels) {
        checkNameCollisions(getFunctions(), constants());
        ExpressionTransforms expressionTransforms = new ExpressionTransforms();

        Map<Reference, TensorType> featureTypes = featureTypes();
        // Function compiling first pass: compile inline functions without resolving other functions
        Map<String, RankingExpressionFunction> inlineFunctions =
                compileFunctions(this::getInlineFunctions, queryProfiles, featureTypes, importedModels, Collections.emptyMap(), expressionTransforms);

        firstPhaseRanking = compile(this.getFirstPhase(), queryProfiles, featureTypes, importedModels, constants(), inlineFunctions, expressionTransforms);
        secondPhaseRanking = compile(this.getSecondPhase(), queryProfiles, featureTypes, importedModels, constants(), inlineFunctions, expressionTransforms);

        // Function compiling second pass: compile all functions and insert previously compiled inline functions
        // TODO: This merges all functions from inherited profiles too and erases inheritance information. Not good.
        functions = compileFunctions(this::getFunctions, queryProfiles, featureTypes, importedModels, inlineFunctions, expressionTransforms);
        allFunctionsCached = null;
    }

    private void checkNameCollisions(Map<String, RankingExpressionFunction> functions, Map<Reference, Constant> constants) {
        for (var functionEntry : functions.entrySet()) {
            if (constants.containsKey(FeatureNames.asConstantFeature(functionEntry.getKey())))
                throw new IllegalArgumentException("Cannot have both a constant and function named '" +
                                                   functionEntry.getKey() + "'");
        }
    }

    private Map<String, RankingExpressionFunction> getInlineFunctions() {
        return getFunctions().entrySet().stream().filter(x -> x.getValue().inline())
                             .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, RankingExpressionFunction> compileFunctions(Supplier<Map<String, RankingExpressionFunction>> functions,
                                                                    QueryProfileRegistry queryProfiles,
                                                                    Map<Reference, TensorType> featureTypes,
                                                                    ImportedMlModels importedModels,
                                                                    Map<String, RankingExpressionFunction> inlineFunctions,
                                                                    ExpressionTransforms expressionTransforms) {
        Map<String, RankingExpressionFunction> compiledFunctions = new LinkedHashMap<>();
        Map.Entry<String, RankingExpressionFunction> entry;
        // Compile all functions. Why iterate in such a complicated way?
        // Because some functions (imported models adding generated functions) may add other functions during compiling.
        // A straightforward iteration will either miss those functions, or may cause a ConcurrentModificationException
        while (null != (entry = findUncompiledFunction(functions.get(), compiledFunctions.keySet()))) {
            RankingExpressionFunction rankingExpressionFunction = entry.getValue();
            RankingExpressionFunction compiled = compile(rankingExpressionFunction, queryProfiles, featureTypes,
                                                         importedModels, constants(), inlineFunctions,
                                                         expressionTransforms);
            compiledFunctions.put(entry.getKey(), compiled);
        }
        return compiledFunctions;
    }

    private static Map.Entry<String, RankingExpressionFunction> findUncompiledFunction(Map<String, RankingExpressionFunction> functions,
                                                                                       Set<String> compiledFunctionNames) {
        for (Map.Entry<String, RankingExpressionFunction> entry : functions.entrySet()) {
            if ( ! compiledFunctionNames.contains(entry.getKey()))
                return entry;
        }
        return null;
    }

    private RankingExpressionFunction compile(RankingExpressionFunction function,
                                              QueryProfileRegistry queryProfiles,
                                              Map<Reference, TensorType> featureTypes,
                                              ImportedMlModels importedModels,
                                              Map<Reference, Constant> constants,
                                              Map<String, RankingExpressionFunction> inlineFunctions,
                                              ExpressionTransforms expressionTransforms) {
        if (function == null) return null;

        RankProfileTransformContext context = new RankProfileTransformContext(this,
                                                                              queryProfiles,
                                                                              featureTypes,
                                                                              importedModels,
                                                                              constants,
                                                                              inlineFunctions);
        RankingExpression expression = expressionTransforms.transform(function.function().getBody(), context);
        for (Map.Entry<String, String> rankProperty : context.rankProperties().entrySet()) {
            addRankProperty(rankProperty.getKey(), rankProperty.getValue());
        }
        return function.withExpression(expression);
    }

    /**
     * Creates a context containing the type information of all constants, attributes and query profiles
     * referable from this rank profile.
     */
    public MapEvaluationTypeContext typeContext(QueryProfileRegistry queryProfiles) {
        return typeContext(queryProfiles, featureTypes());
    }

    public MapEvaluationTypeContext typeContext() { return typeContext(new QueryProfileRegistry()); }

    private Map<Reference, TensorType> featureTypes() {
        Map<Reference, TensorType> featureTypes = inputs().values().stream()
                                                          .collect(Collectors.toMap(input -> input.name(), input -> input.type()));
        allFields().forEach(field -> addAttributeFeatureTypes(field, featureTypes));
        allImportedFields().forEach(field -> addAttributeFeatureTypes(field, featureTypes));
        return featureTypes;
    }
    
    public MapEvaluationTypeContext typeContext(QueryProfileRegistry queryProfiles,
                                                Map<Reference, TensorType> featureTypes) {
        MapEvaluationTypeContext context = new MapEvaluationTypeContext(getExpressionFunctions(), featureTypes);

        constants().forEach((k, v) -> context.setType(k, v.type()));

        // Add query features from all rank profile types
        for (QueryProfileType queryProfileType : queryProfiles.getTypeRegistry().allComponents()) {
            for (FieldDescription field : queryProfileType.declaredFields().values()) {
                TensorType type = field.getType().asTensorType();
                Optional<Reference> feature = Reference.simple(field.getName());
                if ( feature.isEmpty() || ! feature.get().name().equals("query")) continue;
                if (featureTypes.containsKey(feature.get())) continue; // Explicit feature types (from inputs) overrides

                TensorType existingType = context.getType(feature.get());
                if ( ! Objects.equals(existingType, context.defaultTypeOf(feature.get())))
                    type = existingType.dimensionwiseGeneralizationWith(type).orElseThrow( () ->
                        new IllegalArgumentException(queryProfileType + " contains query feature " + feature.get() +
                                                     " with type " + field.getType().asTensorType() +
                                                     ", but this is already defined in another query profile with type " +
                                                     context.getType(feature.get())));
                context.setType(feature.get(), type);
            }
        }

        // Add output types for ONNX models
        for (var model : onnxModels().values()) {
            Arguments args = new Arguments(new ReferenceNode(model.getName()));
            Map<String, TensorType> inputTypes = resolveOnnxInputTypes(model, context);

            TensorType defaultOutputType = model.getTensorType(model.getDefaultOutput(), inputTypes);
            context.setType(new Reference("onnxModel", args, null), defaultOutputType);

            for (Map.Entry<String, String> mapping : model.getOutputMap().entrySet()) {
                TensorType type = model.getTensorType(mapping.getKey(), inputTypes);
                context.setType(new Reference("onnxModel", args, mapping.getValue()), type);
            }
        }
        return context;
    }

    private Map<String, TensorType> resolveOnnxInputTypes(OnnxModel model, MapEvaluationTypeContext context) {
        Map<String, TensorType> inputTypes = new HashMap<>();
        for (String onnxInputName : model.getInputMap().keySet()) {
            resolveOnnxInputType(onnxInputName, model, context).ifPresent(type -> inputTypes.put(onnxInputName, type));
        }
        return inputTypes;
    }

    private Optional<TensorType> resolveOnnxInputType(String onnxInputName, OnnxModel model, MapEvaluationTypeContext context) {
        String source = model.getInputMap().get(onnxInputName);
        if (source != null) {
            // Source is either a simple reference (query/attribute/constant/rankingExpression)...
            Optional<Reference> reference = Reference.simple(source);
            if (reference.isPresent()) {
                if (reference.get().name().equals("rankingExpression") && reference.get().simpleArgument().isPresent()) {
                    source = reference.get().simpleArgument().get();  // look up function below
                } else {
                    return Optional.of(context.getType(reference.get()));
                }
            }
            // ... or a function
            ExpressionFunction func = context.getFunction(source);
            if (func != null) {
                return Optional.of(func.getBody().type(context));
            }
        }
        return Optional.empty();  // if this context does not contain this input
    }

    private void addAttributeFeatureTypes(ImmutableSDField field, Map<Reference, TensorType> featureTypes) {
        Attribute attribute = field.getAttribute();
        field.getAttributes().forEach((k, a) -> {
            String name = k;
            if (attribute == a)                              // this attribute should take the fields name
                name = field.getName();                      // switch to that - it is separate for imported fields
            featureTypes.put(FeatureNames.asAttributeFeature(name),
                            a.tensorType().orElse(TensorType.empty));
        });
    }

    @Override
    public String toString() {
        return "rank profile '" + name() + "'";
    }

    /**
     * A rank setting. The identity of a rank setting is its field name and type (not value).
     * A rank setting is immutable.
     */
    public static class RankSetting implements Serializable {

        private final String fieldName;

        private final Type type;

        /** The rank value */
        private final Object value;

        public enum Type {

            RANKTYPE("rank-type"),
            LITERALBOOST("literal-boost"),
            WEIGHT("weight"),
            PREFERBITVECTOR("preferbitvector",true);

            private final String name;

            /** True if this setting really pertains to an index, not a field within an index */
            private final boolean isIndexLevel;

            Type(String name) {
                this(name,false);
            }

            Type(String name,boolean isIndexLevel) {
                this.name = name;
                this.isIndexLevel=isIndexLevel;
            }

            /** True if this setting really pertains to an index, not a field within an index */
            public boolean isIndexLevel() { return isIndexLevel; }

            /** Returns the name of this type */
            public String getName() {
                return name;
            }

            @Override
            public String toString() {
                return "type " + name;
            }

        }

        public RankSetting(String fieldName, RankSetting.Type type, Object value) {
            this.fieldName = fieldName;
            this.type = type;
            this.value = value;
        }

        public String getFieldName() { return fieldName; }

        public Type getType() { return type; }

        public Object getValue() { return value; }

        /** Returns the value as an int, or a negative value if it is not an integer */
        public int getIntValue() {
            if (value instanceof Integer) {
                return ((Integer)value);
            }
            else {
                return -1;
            }
        }

        @Override
        public int hashCode() {
            return fieldName.hashCode() + 17 * type.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof RankSetting)) {
                return false;
            }
            RankSetting other = (RankSetting)object;
            return
                    fieldName.equals(other.fieldName) &&
                    type.equals(other.type);
        }

        @Override
        public String toString() {
            return type + " setting " + fieldName + ": " + value;
        }

    }

    /** A rank property. Rank properties are Value Objects */
    public static class RankProperty implements Serializable {

        private final String name;
        private final String value;

        public RankProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }

        public String getValue() { return value; }

        @Override
        public int hashCode() {
            return name.hashCode() + 17 * value.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (! (object instanceof RankProperty)) return false;
            RankProperty other=(RankProperty)object;
            return (other.name.equals(this.name) && other.value.equals(this.value));
        }

        @Override
        public String toString() {
            return name + " = " + value;
        }

    }

    /** A function in a rank profile */
    public static class RankingExpressionFunction {

        private ExpressionFunction function;

        /** True if this should be inlined into calling expressions. Useful for very cheap functions. */
        private final boolean inline;

        RankingExpressionFunction(ExpressionFunction function, boolean inline) {
            this.function = function;
            this.inline = inline;
        }

        public void setReturnType(TensorType type) {
            this.function = function.withReturnType(type);
        }

        public ExpressionFunction function() { return function; }

        public boolean inline() {
            return inline && function.arguments().isEmpty(); // only inline no-arg functions;
        }

        RankingExpressionFunction withExpression(RankingExpression expression) {
            return new RankingExpressionFunction(function.withBody(expression), inline);
        }

        @Override
        public String toString() {
            return function.toString();
        }

    }

    public static final class DiversitySettings {

        private String attribute = null;
        private int minGroups = 0;
        private double cutoffFactor = 10;
        private Diversity.CutoffStrategy cutoffStrategy = Diversity.CutoffStrategy.loose;

        public void setAttribute(String value) { attribute = value; }
        public void setMinGroups(int value) { minGroups = value; }
        public void setCutoffFactor(double value) { cutoffFactor = value; }
        public void setCutoffStrategy(Diversity.CutoffStrategy strategy) { cutoffStrategy = strategy; }
        public String getAttribute() { return attribute; }
        public int getMinGroups() { return minGroups; }
        public double getCutoffFactor() { return cutoffFactor; }
        public Diversity.CutoffStrategy getCutoffStrategy() { return cutoffStrategy; }

        void checkValid() {
            if (attribute == null || attribute.isEmpty()) {
                throw new IllegalArgumentException("'diversity' did not set non-empty diversity attribute name.");
            }
            if (minGroups <= 0) {
                throw new IllegalArgumentException("'diversity' did not set min-groups > 0");
            }
            if (cutoffFactor < 1.0) {
                throw new IllegalArgumentException("diversity.cutoff.factor must be larger or equal to 1.0.");
            }
        }
    }

    public static class MatchPhaseSettings {

        private String attribute = null;
        private boolean ascending = false;
        private int maxHits = 0; // try to get this many hits before degrading the match phase
        private double maxFilterCoverage = 0.2; // Max coverage of original corpus that will trigger the filter.
        private DiversitySettings diversity = null;
        private double evaluationPoint = 0.20;
        private double prePostFilterTippingPoint = 1.0;

        public void setDiversity(DiversitySettings value) {
            value.checkValid();
            diversity = value;
        }

        public void setAscending(boolean value) { ascending = value; }
        public void setAttribute(String value) { attribute = value; }
        public void setMaxHits(int value) { maxHits = value; }
        public void setMaxFilterCoverage(double value) { maxFilterCoverage = value; }
        public void setEvaluationPoint(double evaluationPoint) { this.evaluationPoint = evaluationPoint; }
        public void setPrePostFilterTippingPoint(double prePostFilterTippingPoint) { this.prePostFilterTippingPoint = prePostFilterTippingPoint; }

        public boolean                getAscending() { return ascending; }
        public String                 getAttribute() { return attribute; }
        public int                      getMaxHits() { return maxHits; }
        public double         getMaxFilterCoverage() { return maxFilterCoverage; }
        public DiversitySettings      getDiversity() { return diversity; }
        public double           getEvaluationPoint() { return evaluationPoint; }
        public double getPrePostFilterTippingPoint() { return prePostFilterTippingPoint; }

        public void checkValid() {
            if (attribute == null) {
                throw new IllegalArgumentException("match-phase did not set any attribute");
            }
            if (! (maxHits > 0)) {
                throw new IllegalArgumentException("match-phase did not set max-hits > 0");
            }
        }

    }

    public static class TypeSettings {

        private final Map<String, String> types = new HashMap<>();

        void addType(String name, String type) {
            types.put(name, type);
        }

        public Map<String, String> getTypes() {
            return Collections.unmodifiableMap(types);
        }

    }

    public static final class Input {

        private final Reference name;
        private final TensorType type;
        private final Optional<Tensor> defaultValue;

        public Input(Reference name, TensorType type, Optional<Tensor> defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        public Reference name() { return name; }
        public TensorType type() { return type; }
        public Optional<Tensor> defaultValue() { return defaultValue; }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if ( ! (o instanceof Input)) return false;
            Input other = (Input)o;
            if ( ! other.name().equals(this.name())) return false;
            if ( ! other.type().equals(this.type())) return false;
            if ( ! other.defaultValue().equals(this.defaultValue())) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, defaultValue);
        }

        @Override
        public String toString() {
            return "input '" + name + "' " + type +
                   (defaultValue().isPresent() ? ":" + defaultValue.get().toAbbreviatedString() : "");
        }

    }

    public static final class Constant {

        private final Reference name;
        private final TensorType type;

        // One of these are non-empty
        private final Optional<Tensor> value;
        private final Optional<String> valuePath;

        // Always set only if valuePath is set
        private final Optional<DistributableResource.PathType> pathType;

        public Constant(Reference name, Tensor value) {
            this(name, value.type(), Optional.of(value), Optional.empty(), Optional.empty());
        }

        public Constant(Reference name, TensorType type, String valuePath) {
            this(name, type, Optional.empty(), Optional.of(valuePath), Optional.of(DistributableResource.PathType.FILE));
        }

        public Constant(Reference name, TensorType type, String valuePath, DistributableResource.PathType pathType) {
            this(name, type, Optional.empty(), Optional.of(valuePath), Optional.of(pathType));
        }

        private Constant(Reference name, TensorType type, Optional<Tensor> value,
                         Optional<String> valuePath,  Optional<DistributableResource.PathType> pathType) {
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.value = Objects.requireNonNull(value);
            this.valuePath = Objects.requireNonNull(valuePath);
            this.pathType = Objects.requireNonNull(pathType);

            if (type.dimensions().stream().anyMatch(d -> d.isIndexed() && d.size().isEmpty()))
                throw new IllegalArgumentException("Illegal type of constant " + name + " type " + type +
                                                   ": Dense tensor dimensions must have a size");
        }

        public Reference name() { return name; }
        public TensorType type() { return type; }

        /** Returns the value of this, if its path is empty. */
        public Optional<Tensor> value() { return value; }

        /** Returns the path to the value of this, if its value is empty. */
        public Optional<String> valuePath() { return valuePath; }

        /** Returns the path type, if valuePath is set. */
        public Optional<DistributableResource.PathType> pathType() { return pathType; }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if ( ! (o instanceof Constant)) return false;
            Constant other = (Constant)o;
            if ( ! other.name().equals(this.name())) return false;
            if ( ! other.type().equals(this.type())) return false;
            if ( ! other.value().equals(this.value())) return false;
            if ( ! other.valuePath().equals(this.valuePath())) return false;
            if ( ! other.pathType().equals(this.pathType())) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, value, valuePath, pathType);
        }

        @Override
        public String toString() {
            return "constant '" + name + "' " + type + ":" +
                   (value().isPresent() ? value.get().toAbbreviatedString() : " file:" + valuePath.get());
        }

    }

    private static class CachedFunctions {

        private final Map<String, RankingExpressionFunction> allRankingExpressionFunctions;

        private final ImmutableMap<String, ExpressionFunction> allExpressionFunctions;

        CachedFunctions(Map<String, RankingExpressionFunction> functions) {
            allRankingExpressionFunctions = functions;
            ImmutableMap.Builder<String,ExpressionFunction> mapBuilder = new ImmutableMap.Builder<>();
            for (var entry : functions.entrySet()) {
                ExpressionFunction function = entry.getValue().function();
                mapBuilder.put(function.getName(), function);
            }
            allExpressionFunctions = mapBuilder.build();
        }

    }

}
