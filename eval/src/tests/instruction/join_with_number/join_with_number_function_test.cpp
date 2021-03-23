// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/eval/eval/tensor_function.h>
#include <vespa/eval/eval/test/eval_fixture.h>
#include <vespa/eval/eval/test/gen_spec.h>
#include <vespa/eval/instruction/join_with_number_function.h>

#include <vespa/vespalib/util/stringfmt.h>

using namespace vespalib;
using namespace vespalib::eval;
using namespace vespalib::eval::test;
using namespace vespalib::eval::tensor_function;

using vespalib::make_string_short::fmt;

using Primary = JoinWithNumberFunction::Primary;

namespace vespalib::eval {

std::ostream &operator<<(std::ostream &os, Primary primary)
{
    switch(primary) {
    case Primary::LHS: return os << "LHS";
    case Primary::RHS: return os << "RHS";
    }
    abort();
}

}

struct FunInfo {
    using LookFor = JoinWithNumberFunction;
    Primary primary;
    bool inplace;
    void verify(const EvalFixture &fixture, const LookFor &fun) const {
        EXPECT_TRUE(fun.result_is_mutable());
        EXPECT_EQUAL(fun.primary(), primary);
        EXPECT_EQUAL(fun.inplace(), inplace);
        if (inplace) {
            size_t idx = (fun.primary() == Primary::LHS) ? 0 : 1;
            EXPECT_EQUAL(fixture.result_value().cells().data,
                         fixture.param_value(idx).cells().data);
        }
    }
};

void verify_optimized(const vespalib::string &expr, Primary primary, bool inplace) {
    // fprintf(stderr, "%s\n", expr.c_str());
    const auto stable_types = CellTypeSpace({CellType::FLOAT, CellType::DOUBLE}, 2);
    FunInfo stable_details{primary, inplace};
    TEST_DO(EvalFixture::verify<FunInfo>(expr, {stable_details}, stable_types));
}

void verify_not_optimized(const vespalib::string &expr) {
    // fprintf(stderr, "%s\n", expr.c_str());
    CellTypeSpace all_types(CellTypeUtils::list_types(), 2);
    TEST_DO(EvalFixture::verify<FunInfo>(expr, {}, all_types));
}

TEST("require that dense number join can be optimized") {
    TEST_DO(verify_optimized("x3y5+reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)+x3y5", Primary::RHS, false));
    TEST_DO(verify_optimized("x3y5*reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)*x3y5", Primary::RHS, false));
}

TEST("require that dense number join can be inplace") {
    TEST_DO(verify_optimized("@x3y5*reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)*@x3y5", Primary::RHS, true));
    TEST_DO(verify_optimized("@x3y5+reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)+@x3y5", Primary::RHS, true));
}

TEST("require that asymmetric operations work") {
    TEST_DO(verify_optimized("x3y5/reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)/x3y5", Primary::RHS, false));
    TEST_DO(verify_optimized("x3y5-reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)-x3y5", Primary::RHS, false));
}

TEST("require that sparse number join can be optimized") {
    TEST_DO(verify_optimized("x3_1z2_1+reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)+x3_1z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1z2_1<reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)<x3_1z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1z2_1+reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)+x3_1z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1z2_1<reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)<x3_1z2_1", Primary::RHS, false));
}

TEST("require that sparse number join can be inplace") {
    TEST_DO(verify_optimized("@x3_1z2_1+reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)+@x3_1z2_1", Primary::RHS, true));
    TEST_DO(verify_optimized("@x3_1z2_1<reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)<@x3_1z2_1", Primary::RHS, true));
}

TEST("require that mixed number join can be optimized") {
    TEST_DO(verify_optimized("x3_1y5z2_1+reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)+x3_1y5z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1y5z2_1<reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)<x3_1y5z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1y5z2_1+reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)+x3_1y5z2_1", Primary::RHS, false));
    TEST_DO(verify_optimized("x3_1y5z2_1<reduce(v3,sum)", Primary::LHS, false));
    TEST_DO(verify_optimized("reduce(v3,sum)<x3_1y5z2_1", Primary::RHS, false));
}

TEST("require that mixed number join can be inplace") {
    TEST_DO(verify_optimized("@x3_1y5z2_1+reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)+@x3_1y5z2_1", Primary::RHS, true));
    TEST_DO(verify_optimized("@x3_1y5z2_1<reduce(v3,sum)", Primary::LHS, true));
    TEST_DO(verify_optimized("reduce(v3,sum)<@x3_1y5z2_1", Primary::RHS, true));
}

TEST("require that inappropriate cases are not optimized") {
    for (vespalib::string lhs: {"y5", "x3_1z2_1", "x3_1y5z2_1"}) {
        for (vespalib::string rhs: {"y5", "x3_1z2_1", "x3_1y5z2_1"}) {
            auto expr = fmt("%s$1*%s$2", lhs.c_str(), rhs.c_str());
            verify_not_optimized(expr);
        }
        verify_optimized(fmt("reduce(v3,sum)*%s", lhs.c_str()), Primary::RHS, false);
        verify_optimized(fmt("%s*reduce(v3,sum)", lhs.c_str()), Primary::LHS, false);
    }
    // two scalars -> not optimized
    verify_not_optimized("reduce(v3,sum)*reduce(k4,sum)");
}

TEST_MAIN() { TEST_RUN_ALL(); }
