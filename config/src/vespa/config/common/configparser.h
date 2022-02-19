// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "types.h"
#include <vespa/vespalib/util/stringfmt.h>
#include <set>
#include <cerrno>

namespace config {

/**
 * To reduce the need for code in autogenerated config classes, these
 * helper functions exist to help parsing.
 */
class ConfigParser {
public:
    class Cfg {
    public:
        Cfg(const std::vector<vespalib::string> & v)
            : _cfg(&v[0]), _sz(v.size())
        { }
        Cfg(const std::vector<vespalib::string, vespalib::allocator_large<vespalib::string>> & v) :
            _cfg(&v[0]),
            _sz(v.size())
        { }
        size_t size() const { return _sz; }
        const vespalib::string & operator[] (size_t idx) const { return _cfg[idx]; }
    private:
        const vespalib::string * _cfg;
        size_t                   _sz;
    };
private:
    static StringVector getLinesForKey(vespalib::stringref key, Cfg config);

    static std::vector<StringVector> splitArray(Cfg config);
    static std::map<vespalib::string, StringVector> splitMap(Cfg config);

    static vespalib::string deQuote(const vespalib::string & source);
    static void throwNoDefaultValue(vespalib::stringref key);

    template<typename T>
    static T convert(const StringVector & config);

    static vespalib::string arrayToString(Cfg config);

    template<typename T>
    static T parseInternal(vespalib::stringref key, Cfg config);
    template<typename T>
    static T parseInternal(vespalib::stringref key, Cfg config, T defaultValue);

    template<typename V>
    static V parseArrayInternal(vespalib::stringref key, Cfg config);
    template<typename T>
    static std::map<vespalib::string, T> parseMapInternal(vespalib::stringref key, Cfg config);
    template<typename T>
    static T parseStructInternal(vespalib::stringref key, Cfg config);

public:
    static void stripLinesForKey(vespalib::stringref key, std::set<vespalib::string>& config);
    static std::set<vespalib::string> getUniqueNonWhiteSpaceLines(Cfg config);
    static vespalib::string stripWhitespace(vespalib::stringref source);

    template<typename T>
    static T parse(vespalib::stringref key, Cfg config) {
        return parseInternal<T>(key, config);
    }
    template<typename T>
    static T parse(vespalib::stringref key, Cfg config, T defaultValue) {
        return parseInternal(key, config, defaultValue);
    }

    template<typename V>
    static V parseArray(vespalib::stringref key, Cfg config) {
        return parseArrayInternal<V>(key, config);
    }

    template<typename T>
    static std::map<vespalib::string, T> parseMap(vespalib::stringref key, Cfg config) {
        return parseMapInternal<T>(key, config);
    }

    template<typename T>
    static T parseStruct(vespalib::stringref key, Cfg config) {
        return parseStructInternal<T>(key, config);
    }

};

template<typename T>
T
ConfigParser::parseInternal(vespalib::stringref key, Cfg config)
{
    StringVector lines = getLinesForKey(key, config);

    if (lines.size() == 0) {
        throwNoDefaultValue(key);
    }
    return convert<T>(lines);
}

template<typename T>
T
ConfigParser::parseInternal(vespalib::stringref key, Cfg config, T defaultValue)
{
    StringVector lines = getLinesForKey(key, config);

    if (lines.size() == 0) {
        return defaultValue;
    }

    return convert<T>(lines);
}

template<typename T>
T
ConfigParser::convert(const StringVector & lines) {
    return T(lines);
}

template<typename T>
std::map<vespalib::string, T>
ConfigParser::parseMapInternal(vespalib::stringref key, Cfg config)
{
    StringVector lines = getLinesForKey(key, config);
    using SplittedMap = std::map<vespalib::string, StringVector>;
    SplittedMap s = splitMap(lines);
    std::map<vespalib::string, T> retval;
    for (const auto & e : s) {
        retval[e.first] = convert<T>(e.second);
    }
    return retval;
}

template<typename V>
V
ConfigParser::parseArrayInternal(vespalib::stringref key, Cfg config)
{
    StringVector lines = getLinesForKey(key, config);
    std::vector<StringVector> split = splitArray(lines);

    V retval;
    retval.reserve(split.size());
    for (uint32_t i = 0; i < split.size(); i++) {
        retval.push_back(convert<typename V::value_type>(split[i]));
    }

    return retval;
}

template<typename T>
T
ConfigParser::parseStructInternal(vespalib::stringref key, Cfg config)
{
    StringVector lines = getLinesForKey(key, config);

    return convert<T>(lines);
}

template<>
bool
ConfigParser::convert<bool>(const StringVector & config);

template<>
int32_t
ConfigParser::convert<int32_t>(const StringVector & config);

template<>
int64_t
ConfigParser::convert<int64_t>(const StringVector & config);

template<>
double
ConfigParser::convert<double>(const StringVector & config);

template<>
vespalib::string
ConfigParser::convert<vespalib::string>(const StringVector & config);

} // config

