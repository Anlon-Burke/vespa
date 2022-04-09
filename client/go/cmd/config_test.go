// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/vespa-engine/vespa/client/go/mock"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

func TestConfig(t *testing.T) {
	configHome := t.TempDir()
	assertConfigCommandErr(t, configHome, "Error: invalid option or value: foo = bar\n", "config", "set", "foo", "bar")
	assertConfigCommand(t, configHome, "foo = <unset>\n", "config", "get", "foo")
	assertConfigCommand(t, configHome, "target = local\n", "config", "get", "target")
	assertConfigCommand(t, configHome, "", "config", "set", "target", "hosted")
	assertConfigCommand(t, configHome, "target = hosted\n", "config", "get", "target")
	assertConfigCommand(t, configHome, "", "config", "set", "target", "cloud")
	assertConfigCommand(t, configHome, "target = cloud\n", "config", "get", "target")
	assertConfigCommand(t, configHome, "", "config", "set", "target", "http://127.0.0.1:8080")
	assertConfigCommand(t, configHome, "", "config", "set", "target", "https://127.0.0.1")
	assertConfigCommand(t, configHome, "target = https://127.0.0.1\n", "config", "get", "target")

	assertConfigCommandErr(t, configHome, "Error: invalid application: \"foo\"\n", "config", "set", "application", "foo")
	assertConfigCommand(t, configHome, "application = <unset>\n", "config", "get", "application")
	assertConfigCommand(t, configHome, "", "config", "set", "application", "t1.a1.i1")
	assertConfigCommand(t, configHome, "application = t1.a1.i1\n", "config", "get", "application")

	assertConfigCommand(t, configHome, "", "config", "set", "wait", "60")
	assertConfigCommandErr(t, configHome, "Error: wait option must be an integer >= 0, got \"foo\"\n", "config", "set", "wait", "foo")
	assertConfigCommand(t, configHome, "wait = 60\n", "config", "get", "wait")
	assertConfigCommand(t, configHome, "wait = 30\n", "config", "get", "--wait", "30", "wait") // flag overrides global config

	assertConfigCommand(t, configHome, "", "config", "set", "quiet", "true")
	assertConfigCommand(t, configHome, "", "config", "set", "quiet", "false")

	assertConfigCommand(t, configHome, "", "config", "set", "instance", "i2")
	assertConfigCommand(t, configHome, "instance = i2\n", "config", "get", "instance")

	assertConfigCommand(t, configHome, "", "config", "set", "application", "t1.a1")
	assertConfigCommand(t, configHome, "application = t1.a1.default\n", "config", "get", "application")
}

func TestLocalConfig(t *testing.T) {
	configHome := t.TempDir()
	// Write a few global options
	assertConfigCommand(t, configHome, "", "config", "set", "instance", "main")
	assertConfigCommand(t, configHome, "", "config", "set", "target", "cloud")

	// Change directory to an application package and write local options
	_, rootDir := mock.ApplicationPackageDir(t, false, false)
	wd, err := os.Getwd()
	require.Nil(t, err)
	t.Cleanup(func() { os.Chdir(wd) })
	require.Nil(t, os.Chdir(rootDir))
	assertConfigCommand(t, configHome, "", "config", "set", "--local", "instance", "foo")
	assertConfigCommand(t, configHome, "instance = foo\n", "config", "get", "instance")
	assertConfigCommand(t, configHome, "instance = bar\n", "config", "get", "--instance", "bar", "instance") // flag overrides local config

	// get --local prints only options set in local config
	assertConfigCommand(t, configHome, "instance = foo\n", "config", "get", "--local")

	// get reads global option if unset locally
	assertConfigCommand(t, configHome, "target = cloud\n", "config", "get", "target")

	// Only locally set options are written
	localConfig, err := os.ReadFile(filepath.Join(rootDir, ".vespa", "config.yaml"))
	require.Nil(t, err)
	assert.Equal(t, "instance: foo\n", string(localConfig))

	// Changing back to original directory reads from global config
	require.Nil(t, os.Chdir(wd))
	assertConfigCommand(t, configHome, "instance = main\n", "config", "get", "instance")
	assertConfigCommand(t, configHome, "target = cloud\n", "config", "get", "target")
}

func assertConfigCommand(t *testing.T, configHome, expected string, args ...string) {
	t.Helper()
	assertEnvConfigCommand(t, configHome, expected, nil, args...)
}

func assertEnvConfigCommand(t *testing.T, configHome, expected string, env []string, args ...string) {
	t.Helper()
	env = append(env, "VESPA_CLI_HOME="+configHome)
	cli, stdout, _ := newTestCLI(t, env...)
	err := cli.Run(args...)
	assert.Nil(t, err)
	assert.Equal(t, expected, stdout.String())
}

func assertConfigCommandErr(t *testing.T, configHome, expected string, args ...string) {
	t.Helper()
	cli, _, stderr := newTestCLI(t)
	err := cli.Run(args...)
	assert.NotNil(t, err)
	assert.Equal(t, expected, stderr.String())
}

func TestUseAPIKey(t *testing.T) {
	cli, _, _ := newTestCLI(t)
	assert.False(t, cli.config.useAPIKey(cli, vespa.PublicSystem, "t1"))

	cli, _, _ = newTestCLI(t, "VESPA_CLI_API_KEY_FILE=/tmp/foo")
	assert.True(t, cli.config.useAPIKey(cli, vespa.PublicSystem, "t1"))

	cli, _, _ = newTestCLI(t, "VESPA_CLI_API_KEY=foo")
	assert.True(t, cli.config.useAPIKey(cli, vespa.PublicSystem, "t1"))

	// Prefer Auth0, if configured
	authContent := `
{
    "version": 1,
    "providers": {
        "auth0": {
            "version": 1,
            "systems": {
                "public": {
					"access_token": "...",
					"scopes": ["openid", "offline_access"],
					"expires_at": "2030-01-01T01:01:01.000001+01:00"
				}
			}
		}
	}
}`
	cli, _, _ = newTestCLI(t, "VESPA_CLI_CLOUD_SYSTEM=public")
	_, err := os.Create(filepath.Join(cli.config.homeDir, "t2.api-key.pem"))
	require.Nil(t, err)
	assert.True(t, cli.config.useAPIKey(cli, vespa.PublicSystem, "t2"))
	require.Nil(t, os.WriteFile(filepath.Join(cli.config.homeDir, "auth.json"), []byte(authContent), 0600))
	assert.False(t, cli.config.useAPIKey(cli, vespa.PublicSystem, "t2"))
}
