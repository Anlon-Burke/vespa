// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Author: arnej

package jvm

import (
	"github.com/vespa-engine/vespa/client/go/defaults"
)

const (
	DEFAULT_MAIN_CLASS = "com.yahoo.jdisc.core.StandaloneMain"
	DEFAULT_CP_FILE    = "lib/jars/jdisc_core-jar-with-dependencies.jar"
)

func (opts *Options) AddCommonJdiscProperties() {
	logCtlDir := defaults.UnderVespaHome("var/db/vespa/logcontrol")
	opts.fixSpec.FixDir(logCtlDir)
	opts.AddOption("-Djdisc.bundle.path=" + defaults.UnderVespaHome("lib/jars"))
	opts.AddOption("-Djdisc.logger.enabled=false")
	opts.AddOption("-Djdisc.logger.level=WARNING")
	opts.AddOption("-Dvespa.log.control.dir=" + logCtlDir)
}
