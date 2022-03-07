// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package cmd

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/viper"
	"github.com/vespa-engine/vespa/client/go/mock"
)

func newTestCLI(t *testing.T, envVars ...string) (*CLI, *bytes.Buffer, *bytes.Buffer) {
	t.Cleanup(viper.Reset)
	homeDir := filepath.Join(t.TempDir(), ".vespa")
	cacheDir := filepath.Join(t.TempDir(), ".cache", "vespa")
	env := []string{"VESPA_CLI_HOME=" + homeDir, "VESPA_CLI_CACHE_DIR=" + cacheDir}
	env = append(env, envVars...)
	var (
		stdout bytes.Buffer
		stderr bytes.Buffer
	)
	cli, err := New(&stdout, &stderr, env)
	if err != nil {
		t.Fatal(err)
	}
	cli.httpClient = &mock.HTTPClient{}
	cli.exec = &mock.Exec{}
	return cli, &stdout, &stderr
}

func mockApplicationPackage(t *testing.T, java bool) string {
	dir := t.TempDir()
	appDir := filepath.Join(dir, "src", "main", "application")
	if err := os.MkdirAll(appDir, 0755); err != nil {
		t.Fatal(err)
	}
	servicesXML := filepath.Join(appDir, "services.xml")
	if _, err := os.Create(servicesXML); err != nil {
		t.Fatal(err)
	}
	if java {
		if _, err := os.Create(filepath.Join(dir, "pom.xml")); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}
