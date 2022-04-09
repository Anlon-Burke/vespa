// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa config command
// author: bratseth

package cmd

import (
	"crypto/tls"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/fatih/color"
	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
	"github.com/spf13/viper"
	"github.com/vespa-engine/vespa/client/go/auth/auth0"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

const (
	configName = "config"
	configType = "yaml"
)

func newConfigCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "config",
		Short: "Configure persistent values for global flags",
		Long: `Configure persistent values for global flags.

This command allows setting persistent values for global flags. On future
invocations the flag can then be omitted as it is read from the config file
instead.

Configuration is written to $HOME/.vespa by default. This path can be
overridden by setting the VESPA_CLI_HOME environment variable.

When setting an option locally, the configuration is written to .vespa in the
working directory, where that directory is assumed to be a Vespa application
directory. This allows you have separate configuration options per application.

Vespa CLI chooses the value for a given option in the following order, from
most to least preferred:

1. Flag value specified on the command line
2. Local config value
3. Global config value
4. Default value

The following flags/options can be configured:

application

Specifies the application ID to manage. It has three parts, separated by
dots, with the third part being optional. This is only relevant for the "cloud"
and "hosted" targets. See https://cloud.vespa.ai/en/tenant-apps-instances for
more details. This has no default value. Examples: tenant1.app1,
tenant1.app1.instance1

color

Controls how Vespa CLI uses colors. Setting this to "auto" (default) enables
colors if supported by the terminal, "never" completely disables colors and
"always" enables colors unilaterally.

instance

Specifies the instance of the application to manage. When specified, this takes
precedence over the instance specified as part of application. This has no
default value. Example: instance2

quiet

Print only errors.

target

Specifies the target to use for commands that interact with a Vespa platform,
e.g. vespa deploy or vespa query. Possible values are:

- local: (default) Connect to a Vespa platform running at localhost
- cloud: Connect to Vespa Cloud
- hosted: Connect to hosted Vespa (internal platform)
- *url*: Connect to a platform running at given URL.

wait

Specifies the number of seconds to wait for a service to become ready or
deployment to complete. Use this to have a potentially long-running command
block until the operation is complete, e.g. with vespa deploy. Defaults to 0
(no waiting)

zone

Specifies a custom dev or perf zone to use when connecting to a Vespa platform.
This is only relevant for cloud and hosted targets. By default, a zone is
chosen automatically. See https://cloud.vespa.ai/en/reference/zones for
available zones. Examples: dev.aws-us-east-1c, perf.aws-us-east-1c
`,
		DisableAutoGenTag: true,
		SilenceUsage:      false,
		Args:              cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("invalid command: %s", args[0])
		},
	}
}

func newConfigSetCmd(cli *CLI) *cobra.Command {
	var localArg bool
	cmd := &cobra.Command{
		Use:   "set option-name value",
		Short: "Set a configuration option.",
		Example: `# Set the target to Vespa Cloud
$ vespa config set target cloud

# Set application, without a specific instance. The instance will be named "default"
$ vespa config set application my-tenant.my-application

# Set application with a specific instance
$ vespa config set application my-tenant.my-application.my-instance

# Set the instance explicitly. This will take precedence over an instance specified as part of the application option.
$ vespa config set instance other-instance

# Set an option in local configuration, for the current application only
$ vespa config set --local wait 600
`,
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		Args:              cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			config := cli.config
			if localArg {
				// Need an application package in working directory to allow local configuration
				if _, err := cli.applicationPackageFrom(nil, false); err != nil {
					return fmt.Errorf("failed to write local configuration: %w", err)
				}
				if err := cli.config.loadLocalConfigFrom(".", true, true); err != nil {
					return fmt.Errorf("failed to create local configuration: %w", err)
				}
				config = cli.config.local
			}
			if err := config.set(args[0], args[1]); err != nil {
				return err
			}
			return config.write()
		},
	}
	cmd.Flags().BoolVarP(&localArg, "local", "l", false, "Write option to local configuration, i.e. for the current application")
	return cmd
}

func newConfigGetCmd(cli *CLI) *cobra.Command {
	var localArg bool
	cmd := &cobra.Command{
		Use:   "get [option-name]",
		Short: "Show given configuration option, or all configuration options",
		Long: `Show given configuration option, or all configuration options.

By default this command prints the effective configuration for the current
application, i.e. it takes into account any local configuration located in
[working-directory]/.vespa.
`,
		Example: `$ vespa config get
$ vespa config get target
$ vespa config get --local
`,
		Args:              cobra.MaximumNArgs(1),
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		RunE: func(cmd *cobra.Command, args []string) error {
			config := cli.config
			if localArg {
				if err := cli.config.loadLocalConfigFrom(".", false, true); err != nil {
					return fmt.Errorf("failed to load local configuration: %w", err)
				}
				config = cli.config.local
				if config == nil {
					cli.printWarning("no local configuration present")
					return nil
				}
			}
			if len(args) == 0 { // Print all values
				for _, option := range config.list() {
					config.printOption(option)
				}
			} else {
				config.printOption(args[0])
			}
			return nil
		},
	}
	cmd.Flags().BoolVarP(&localArg, "local", "l", false, "Show only local configuration, if any")
	return cmd
}

type Config struct {
	homeDir     string
	cacheDir    string
	environment map[string]string
	local       *Config

	flags *pflag.FlagSet
	viper *viper.Viper
}

type KeyPair struct {
	KeyPair         tls.Certificate
	CertificateFile string
	PrivateKeyFile  string
}

func loadConfig(environment map[string]string, globalFlags *pflag.FlagSet) (*Config, error) {
	home, err := vespaCliHome(environment)
	if err != nil {
		return nil, fmt.Errorf("could not detect config directory: %w", err)
	}
	config, err := loadConfigFrom(home, environment, globalFlags)
	if err != nil {
		return nil, err
	}
	// Load local config from working directory by default, if any
	if err := config.loadLocalConfigFrom(".", false, false); err != nil {
		return nil, err
	}
	return config, nil
}

func loadConfigFrom(dir string, environment map[string]string, globalFlags *pflag.FlagSet) (*Config, error) {
	cacheDir, err := vespaCliCacheDir(environment)
	if err != nil {
		return nil, fmt.Errorf("could not detect cache directory: %w", err)
	}
	c := &Config{
		homeDir:     dir,
		cacheDir:    cacheDir,
		environment: environment,
		flags:       globalFlags,
	}
	v := viper.New()
	v.SetConfigName(configName)
	v.SetConfigType(configType)
	v.AddConfigPath(c.homeDir)
	v.BindPFlags(globalFlags)
	c.viper = v
	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, err
		}
	}
	return c, nil
}

func (c *Config) loadLocalConfigFrom(parent string, create, noFlags bool) error {
	home := filepath.Join(parent, ".vespa")
	_, err := os.Stat(home)
	if err != nil {
		if !os.IsNotExist(err) {
			return err
		}
		if !create {
			return nil
		}
	}
	flags := c.flags
	if noFlags {
		flags = &pflag.FlagSet{}
	}
	config, err := loadConfigFrom(home, c.environment, flags)
	if err != nil {
		return err
	}
	c.local = config
	return nil
}

func (c *Config) write() error {
	if err := os.MkdirAll(c.homeDir, 0700); err != nil {
		return err
	}
	configFile := filepath.Join(c.homeDir, configName+"."+configType)
	if !util.PathExists(configFile) {
		if _, err := os.Create(configFile); err != nil {
			return err
		}
	}
	err := c.viper.WriteConfig()
	return err
}

func (c *Config) targetType() (string, error) {
	targetType, ok := c.get(targetFlag)
	if !ok {
		return "", fmt.Errorf("target is unset")
	}
	return targetType, nil
}

func (c *Config) timeout() (time.Duration, error) {
	wait, ok := c.get(waitFlag)
	if !ok {
		return 0, nil
	}
	secs, err := strconv.Atoi(wait)
	if err != nil {
		return 0, err
	}
	return time.Duration(secs) * time.Second, nil
}

func (c *Config) isQuiet() bool {
	quiet, _ := c.get(quietFlag)
	return quiet == "true"
}

func (c *Config) application() (vespa.ApplicationID, error) {
	app, ok := c.get(applicationFlag)
	if !ok {
		return vespa.ApplicationID{}, errHint(fmt.Errorf("no application specified"), "Try the --"+applicationFlag+" flag")
	}
	application, err := vespa.ApplicationFromString(app)
	if err != nil {
		return vespa.ApplicationID{}, errHint(err, "application format is <tenant>.<app>[.<instance>]")
	}
	instance, ok := c.get(instanceFlag)
	if ok {
		application.Instance = instance
	}
	return application, nil
}

func (c *Config) deploymentIn(system vespa.System) (vespa.Deployment, error) {
	zone := system.DefaultZone
	zoneName, ok := c.get(zoneFlag)
	if ok {
		var err error
		zone, err = vespa.ZoneFromString(zoneName)
		if err != nil {
			return vespa.Deployment{}, err
		}
	}
	app, err := c.application()
	if err != nil {
		return vespa.Deployment{}, err
	}
	return vespa.Deployment{System: system, Application: app, Zone: zone}, nil
}

func (c *Config) certificatePath(app vespa.ApplicationID) (string, error) {
	if override, ok := c.environment["VESPA_CLI_DATA_PLANE_CERT_FILE"]; ok {
		return override, nil
	}
	return c.applicationFilePath(app, "data-plane-public-cert.pem")
}

func (c *Config) privateKeyPath(app vespa.ApplicationID) (string, error) {
	if override, ok := c.environment["VESPA_CLI_DATA_PLANE_KEY_FILE"]; ok {
		return override, nil
	}
	return c.applicationFilePath(app, "data-plane-private-key.pem")
}

func (c *Config) x509KeyPair(app vespa.ApplicationID) (KeyPair, error) {
	cert, certOk := c.environment["VESPA_CLI_DATA_PLANE_CERT"]
	key, keyOk := c.environment["VESPA_CLI_DATA_PLANE_KEY"]
	if certOk && keyOk {
		// Use key pair from environment
		kp, err := tls.X509KeyPair([]byte(cert), []byte(key))
		return KeyPair{KeyPair: kp}, err
	}
	privateKeyFile, err := c.privateKeyPath(app)
	if err != nil {
		return KeyPair{}, err
	}
	certificateFile, err := c.certificatePath(app)
	if err != nil {
		return KeyPair{}, err
	}
	kp, err := tls.LoadX509KeyPair(certificateFile, privateKeyFile)
	if err != nil {
		return KeyPair{}, err
	}
	return KeyPair{
		KeyPair:         kp,
		CertificateFile: certificateFile,
		PrivateKeyFile:  privateKeyFile,
	}, nil
}

func (c *Config) apiKeyFileFromEnv() (string, bool) {
	override, ok := c.environment["VESPA_CLI_API_KEY_FILE"]
	return override, ok
}

func (c *Config) apiKeyFromEnv() ([]byte, bool) {
	override, ok := c.environment["VESPA_CLI_API_KEY"]
	return []byte(override), ok
}

func (c *Config) apiKeyPath(tenantName string) string {
	if override, ok := c.apiKeyFileFromEnv(); ok {
		return override
	}
	return filepath.Join(c.homeDir, tenantName+".api-key.pem")
}

func (c *Config) authConfigPath() string {
	return filepath.Join(c.homeDir, "auth.json")
}

func (c *Config) readAPIKey(tenantName string) ([]byte, error) {
	if override, ok := c.apiKeyFromEnv(); ok {
		return override, nil
	}
	return os.ReadFile(c.apiKeyPath(tenantName))
}

// useAPIKey returns true if an API key should be used when authenticating with system.
func (c *Config) useAPIKey(cli *CLI, system vespa.System, tenantName string) bool {
	if _, ok := c.apiKeyFromEnv(); ok {
		return true
	}
	if _, ok := c.apiKeyFileFromEnv(); ok {
		return true
	}
	if !cli.isCI() {
		// Fall back to API key, if present and Auth0 has not been configured
		client, err := auth0.New(c.authConfigPath(), system.Name, system.URL)
		if err != nil || !client.HasCredentials() {
			cli.printWarning("Regular authentication is preferred over API key in a non-CI context", "Authenticate with 'vespa auth login'")
			return util.PathExists(c.apiKeyPath(tenantName))
		}
	}
	return false
}

func (c *Config) readSessionID(app vespa.ApplicationID) (int64, error) {
	sessionPath, err := c.applicationFilePath(app, "session_id")
	if err != nil {
		return 0, err
	}
	b, err := os.ReadFile(sessionPath)
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
}

func (c *Config) writeSessionID(app vespa.ApplicationID, sessionID int64) error {
	sessionPath, err := c.applicationFilePath(app, "session_id")
	if err != nil {
		return err
	}
	return os.WriteFile(sessionPath, []byte(fmt.Sprintf("%d\n", sessionID)), 0600)
}

func (c *Config) applicationFilePath(app vespa.ApplicationID, name string) (string, error) {
	appDir := filepath.Join(c.homeDir, app.String())
	if err := os.MkdirAll(appDir, 0700); err != nil {
		return "", err
	}
	return filepath.Join(appDir, name), nil
}

func (c *Config) list() []string {
	options := c.viper.AllKeys()
	sort.Strings(options)
	return options
}

func (c *Config) isSet(option string) bool { return c.viper.IsSet(option) }

func (c *Config) get(option string) (string, bool) {
	if c.local != nil {
		// when reading from local config, the option must be explicitly set to be considered
		if c.local.isSet(option) {
			if value, ok := c.local.get(option); ok {
				return value, ok
			}
		}
	}
	value := c.viper.GetString(option)
	if value == "" {
		return "", false
	}
	return value, true
}

func (c *Config) set(option, value string) error {
	switch option {
	case targetFlag:
		switch value {
		case vespa.TargetLocal, vespa.TargetCloud, vespa.TargetHosted:
			c.viper.Set(option, value)
			return nil
		}
		if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
			c.viper.Set(option, value)
			return nil
		}
	case applicationFlag:
		app, err := vespa.ApplicationFromString(value)
		if err != nil {
			return err
		}
		c.viper.Set(option, app.String())
		return nil
	case instanceFlag:
		c.viper.Set(option, value)
		return nil
	case waitFlag:
		if n, err := strconv.Atoi(value); err != nil || n < 0 {
			return fmt.Errorf("%s option must be an integer >= 0, got %q", option, value)
		}
		c.viper.Set(option, value)
		return nil
	case colorFlag:
		switch value {
		case "auto", "never", "always":
			c.viper.Set(option, value)
			return nil
		}
	case quietFlag:
		switch value {
		case "true", "false":
			c.viper.Set(option, value)
			return nil
		}
	}
	return fmt.Errorf("invalid option or value: %s = %s", option, value)
}

func (c *Config) printOption(option string) {
	value, ok := c.get(option)
	if !ok {
		faintColor := color.New(color.FgWhite, color.Faint)
		value = faintColor.Sprint("<unset>")
	} else {
		value = color.CyanString(value)
	}
	log.Printf("%s = %s", option, value)
}

func vespaCliHome(env map[string]string) (string, error) {
	home := env["VESPA_CLI_HOME"]
	if home == "" {
		userHome, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		home = filepath.Join(userHome, ".vespa")
	}
	if err := os.MkdirAll(home, 0700); err != nil {
		return "", err
	}
	return home, nil
}

func vespaCliCacheDir(env map[string]string) (string, error) {
	cacheDir := env["VESPA_CLI_CACHE_DIR"]
	if cacheDir == "" {
		userCacheDir, err := os.UserCacheDir()
		if err != nil {
			return "", err
		}
		cacheDir = filepath.Join(userCacheDir, "vespa")
	}
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return "", err
	}
	return cacheDir, nil
}
