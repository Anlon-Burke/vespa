// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa config command
// author: bratseth

package cmd

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/spf13/cobra"
	"github.com/spf13/viper"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

const (
	configName = "config"
	configType = "yaml"
)

var flagToConfigBindings map[string]*cobra.Command = make(map[string]*cobra.Command)

func init() {
	rootCmd.AddCommand(configCmd)
	configCmd.AddCommand(setConfigCmd)
	configCmd.AddCommand(getConfigCmd)
}

var configCmd = &cobra.Command{
	Use:               "config",
	Short:             "Configure default values for flags",
	DisableAutoGenTag: true,
	Run: func(cmd *cobra.Command, args []string) {
		// Root command does nothing
		cmd.Help()
		os.Exit(1)
	},
}

var setConfigCmd = &cobra.Command{
	Use:               "set <option> <value>",
	Short:             "Set a configuration option.",
	Example:           "$ vespa config set target cloud",
	DisableAutoGenTag: true,
	Args:              cobra.ExactArgs(2),
	Run: func(cmd *cobra.Command, args []string) {
		if err := setOption(args[0], args[1]); err != nil {
			log.Print(err)
		} else {
			writeConfig()
		}
	},
}

var getConfigCmd = &cobra.Command{
	Use:               "get [<option>]",
	Short:             "Get a configuration option",
	Example:           "$ vespa config get target",
	Args:              cobra.MaximumNArgs(1),
	DisableAutoGenTag: true,
	Run: func(cmd *cobra.Command, args []string) {
		if len(args) == 0 { // Print all values
			printOption(targetFlag)
			printOption(applicationFlag)
		} else {
			printOption(args[0])
		}
	},
}

func printOption(option string) {
	value, err := getOption(option)
	if err != nil {
		value = color.Faint("<unset>").String()
	} else {
		value = color.Cyan(value).String()
	}
	log.Printf("%s = %s", option, value)
}

func configDir(application string) string {
	home := os.Getenv("VESPA_CLI_HOME")
	if home == "" {
		var err error
		home, err = os.UserHomeDir()
		if err != nil {
			fatalErr(err, "Could not determine configuration directory")
			return ""
		}
	}
	configDir := filepath.Join(home, ".vespa", application)
	if err := os.MkdirAll(configDir, 0755); err != nil {
		fatalErr(err, "Could not create config directory")
		return ""
	}
	return configDir
}

func bindFlagToConfig(option string, command *cobra.Command) {
	flagToConfigBindings[option] = command
}

func readConfig() {
	configDir := configDir("")
	if configDir == "" {
		return
	}
	viper.SetConfigName(configName)
	viper.SetConfigType(configType)
	viper.AddConfigPath(configDir)
	viper.AutomaticEnv()
	for option, command := range flagToConfigBindings {
		viper.BindPFlag(option, command.PersistentFlags().Lookup(option))
	}
	err := viper.ReadInConfig()
	if _, ok := err.(viper.ConfigFileNotFoundError); ok {
		return // Fine
	}
	if err != nil {
		fatalErr(err, "Could not read configuration")
	}
}

func getOption(option string) (string, error) {
	value := viper.GetString(option)
	if value == "" {
		return "", fmt.Errorf("no such option: %q", option)
	}
	return value, nil
}

func setOption(option, value string) error {
	switch option {
	case targetFlag:
		switch value {
		case "local", "cloud":
			viper.Set(option, value)
			return nil
		}
		if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
			viper.Set(option, value)
			return nil
		}
	case applicationFlag:
		if _, err := vespa.ApplicationFromString(value); err != nil {
			return err
		}
		viper.Set(option, value)
		return nil
	case waitFlag:
		if _, err := strconv.ParseUint(value, 10, 32); err != nil {
			return fmt.Errorf("%s option must be an integer >= 0, got %q", option, value)
		}
		viper.Set(option, value)
		return nil
	}
	return fmt.Errorf("invalid option or value: %q: %q", option, value)
}

func writeConfig() {
	configDir := configDir("")
	if configDir == "" {
		return
	}

	if !util.PathExists(configDir) {
		if err := os.MkdirAll(configDir, 0700); err != nil {
			fatalErr(err, "Could not create ", color.Cyan(configDir))
			return
		}
	}

	configFile := filepath.Join(configDir, configName+"."+configType)
	if !util.PathExists(configFile) {
		if _, err := os.Create(configFile); err != nil {
			fatalErr(err, "Could not create ", color.Cyan(configFile))
			return
		}
	}

	if err := viper.WriteConfig(); err != nil {
		fatalErr(err, "Could not write config")
		return
	}
}
