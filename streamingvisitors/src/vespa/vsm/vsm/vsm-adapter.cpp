// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "vsm-adapter.hpp"
#include "docsumconfig.h"
#include "i_matching_elements_filler.h"
#include <vespa/searchlib/common/matching_elements.h>

#include <vespa/log/log.h>
LOG_SETUP(".vsm.vsm-adapter");

using search::docsummary::ResConfigEntry;
using search::docsummary::KeywordExtractor;
using search::MatchingElements;
using config::ConfigSnapshot;

namespace vsm {

GetDocsumsStateCallback::GetDocsumsStateCallback() :
    _summaryFeatures(),
    _rankFeatures(),
    _matching_elements_filler()
{ }

void GetDocsumsStateCallback::FillSummaryFeatures(GetDocsumsState * state, IDocsumEnvironment * env)
{
    (void) env;
    if (_summaryFeatures) { // set the summary features to write to the docsum
        state->_summaryFeatures = _summaryFeatures;
        state->_summaryFeaturesCached = true;
    }
}

void GetDocsumsStateCallback::FillRankFeatures(GetDocsumsState * state, IDocsumEnvironment * env)
{
    (void) env;
    if (_rankFeatures) { // set the rank features to write to the docsum
        state->_rankFeatures = _rankFeatures;
    }
}

void GetDocsumsStateCallback::FillDocumentLocations(GetDocsumsState *state, IDocsumEnvironment * env)
{
    (void) state;
    (void) env;
}

std::unique_ptr<MatchingElements>
GetDocsumsStateCallback::fill_matching_elements(const search::MatchingElementsFields& fields)
{
    if (_matching_elements_filler) {
        return _matching_elements_filler->fill_matching_elements(fields);
    }
    return std::make_unique<MatchingElements>();
}

void
GetDocsumsStateCallback::set_matching_elements_filler(std::unique_ptr<IMatchingElementsFiller> matching_elements_filler)
{
    _matching_elements_filler = std::move(matching_elements_filler);
}

GetDocsumsStateCallback::~GetDocsumsStateCallback() = default;

DocsumTools::FieldSpec::FieldSpec() :
    _outputName(),
    _inputNames(),
    _command(VsmsummaryConfig::Fieldmap::Command::NONE)
{ }

DocsumTools::FieldSpec::~FieldSpec() = default;

DocsumTools::DocsumTools(std::unique_ptr<DynamicDocsumWriter> writer) :
    _writer(std::move(writer)),
    _juniper(),
    _resultClass(),
    _fieldSpecs()
{ }


DocsumTools::~DocsumTools() = default;

bool
DocsumTools::obtainFieldNames(const FastS_VsmsummaryHandle &cfg)
{
    uint32_t defaultSummaryId = getResultConfig()->LookupResultClassId(cfg->outputclass);
    _resultClass = getResultConfig()->LookupResultClass(defaultSummaryId);
    if (_resultClass != NULL) {
        for (uint32_t i = 0; i < _resultClass->GetNumEntries(); ++i) {
            const ResConfigEntry * entry = _resultClass->GetEntry(i);
            _fieldSpecs.push_back(FieldSpec());
            _fieldSpecs.back().setOutputName(entry->_bindname);
            bool found = false;
            if (cfg) {
                // check if we have this summary field in the vsmsummary config
                for (uint32_t j = 0; j < cfg->fieldmap.size() && !found; ++j) {
                    if (entry->_bindname == cfg->fieldmap[j].summary.c_str()) {
                        for (uint32_t k = 0; k < cfg->fieldmap[j].document.size(); ++k) {
                            _fieldSpecs.back().getInputNames().push_back(cfg->fieldmap[j].document[k].field);
                        }
                        _fieldSpecs.back().setCommand(cfg->fieldmap[j].command);
                        found = true;
                    }
                }
            }
            if (!found) {
                // use yourself as input
                _fieldSpecs.back().getInputNames().push_back(entry->_bindname);
            }
        }
    } else {
        LOG(warning, "could not locate result class: '%s'", cfg->outputclass.c_str());
    }
    return true;
}

void
VSMAdapter::configure(const VSMConfigSnapshot & snapshot)
{
    std::lock_guard guard(_lock);
    LOG(debug, "(re-)configure VSM (docsum tools)");

    std::shared_ptr<SummaryConfig>      summary(snapshot.getConfig<SummaryConfig>());
    std::shared_ptr<SummarymapConfig>   summaryMap(snapshot.getConfig<SummarymapConfig>());
    std::shared_ptr<VsmsummaryConfig>   vsmSummary(snapshot.getConfig<VsmsummaryConfig>());
    std::shared_ptr<JuniperrcConfig>    juniperrc(snapshot.getConfig<JuniperrcConfig>());

    _fieldsCfg.set(snapshot.getConfig<VsmfieldsConfig>().release());
    _fieldsCfg.latch();

    LOG(debug, "configureFields(): Size of cfg fieldspec: %zd", _fieldsCfg.get()->fieldspec.size()); // UlfC: debugging
    LOG(debug, "configureFields(): Size of cfg documenttype: %zd", _fieldsCfg.get()->documenttype.size()); // UlfC: debugging
    LOG(debug, "configureSummary(): Size of cfg classes: %zd", summary->classes.size()); // UlfC: debugging
    LOG(debug, "configureSummaryMap(): Size of cfg override: %zd", summaryMap->override.size()); // UlfC: debugging
    LOG(debug, "configureVsmSummary(): Size of cfg fieldmap: %zd", vsmSummary->fieldmap.size()); // UlfC: debugging
    LOG(debug, "configureVsmSummary(): outputclass='%s'", vsmSummary->outputclass.c_str()); // UlfC: debugging

    // init result config
    std::unique_ptr<ResultConfig> resCfg(new ResultConfig());
    if ( ! resCfg->ReadConfig(*summary.get(), _configId.c_str())) {
        throw std::runtime_error("(re-)configuration of VSM (docsum tools) failed due to bad summary config");
    }

    // init keyword extractor
    auto kwExtractor = std::make_unique<KeywordExtractor>(nullptr);
    kwExtractor->AddLegalIndexSpec(_highlightindexes.c_str());
    vespalib::string spec = kwExtractor->GetLegalIndexSpec();
    LOG(debug, "index highlight spec: '%s'", spec.c_str());

    // create dynamic docsum writer
    auto writer = std::make_unique<DynamicDocsumWriter>(resCfg.release(), kwExtractor.release());

    // configure juniper (used when configuring DynamicDocsumConfig)
    _juniperProps = std::make_unique<JuniperProperties>(*juniperrc);
    auto juniper = std::make_unique<juniper::Juniper>(_juniperProps.get(), &_wordFolder);

    // create new docsum tools
    auto docsumTools = std::make_unique<DocsumTools>(std::move(writer));
    docsumTools->setJuniper(std::move(juniper));

    // configure dynamic docsum writer
    DynamicDocsumConfig dynDocsumConfig(docsumTools.get(), docsumTools->getDocsumWriter(), _fieldsCfg.get());
    dynDocsumConfig.configure(*summaryMap.get());

    // configure new docsum tools
    if (docsumTools->obtainFieldNames(vsmSummary)) {
        // latch new docsum tools into production
        _docsumTools.set(docsumTools.release());
        _docsumTools.latch();
    } else {
        throw std::runtime_error("(re-)configuration of VSM (docsum tools) failed");
    }
}

VSMConfigSnapshot::VSMConfigSnapshot(const vespalib::string & configId, const config::ConfigSnapshot & snapshot)
    : _configId(configId),
      _snapshot(std::make_unique<config::ConfigSnapshot>(snapshot))
{ }
VSMConfigSnapshot::~VSMConfigSnapshot() = default;

VSMAdapter::VSMAdapter(const vespalib::string & highlightindexes, const vespalib::string & configId, Fast_WordFolder & wordFolder)
    : _highlightindexes(highlightindexes),
      _configId(configId),
      _wordFolder(wordFolder),
      _fieldsCfg(),
      _docsumTools(),
      _juniperProps(),
      _lock()
{
}


VSMAdapter::~VSMAdapter() = default;

}
