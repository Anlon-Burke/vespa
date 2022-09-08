// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Author: arnej

package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/vespa-engine/vespa/client/go/cmd/deploy"
	"github.com/vespa-engine/vespa/client/go/cmd/logfmt"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

func basename(s string) string {
	parts := strings.Split(s, "/")
	return parts[len(parts)-1]
}

func main() {
	action := basename(os.Args[0])
	if action == "script-utils" && len(os.Args) > 1 {
		action = os.Args[1]
		os.Args = os.Args[1:]
	}
	switch action {
	case "export-env":
		vespa.ExportDefaultEnvToSh()
	case "ipv6-only":
		if vespa.HasOnlyIpV6() {
			os.Exit(0)
		} else {
			os.Exit(1)
		}
	case "vespa-deploy":
		_ = vespa.FindHome()
		cobra := deploy.NewDeployCmd()
		cobra.Execute()
	case "vespa-logfmt":
		_ = vespa.FindHome()
		cobra := logfmt.NewLogfmtCmd()
		cobra.Execute()
	default:
		fmt.Fprintf(os.Stderr, "unknown action '%s'\n", action)
		fmt.Fprintln(os.Stderr, "actions: export-env, ipv6-only, vespa-deploy, vespa-logfmt")
	}
}
