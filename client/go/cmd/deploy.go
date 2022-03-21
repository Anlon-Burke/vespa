// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa deploy command
// Author: bratseth

package cmd

import (
	"fmt"
	"io"
	"log"
	"strconv"

	"github.com/fatih/color"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

func newDeployCmd(cli *CLI) *cobra.Command {
	var (
		zoneArg     string
		logLevelArg string
	)
	cmd := &cobra.Command{
		Use:   "deploy [application-directory]",
		Short: "Deploy (prepare and activate) an application package",
		Long: `Deploy (prepare and activate) an application package.

When this returns successfully the application package has been validated
and activated on config servers. The process of applying it on individual nodes
has started but may not have completed.

If application directory is not specified, it defaults to working directory.

When deploying to Vespa Cloud the system can be overridden by setting the
environment variable VESPA_CLI_CLOUD_SYSTEM. This is intended for internal use
only.`,
		Example: `$ vespa deploy .
$ vespa deploy -t cloud
$ vespa deploy -t cloud -z dev.aws-us-east-1c  # -z can be omitted here as this zone is the default
$ vespa deploy -t cloud -z perf.aws-us-east-1c`,
		Args:              cobra.MaximumNArgs(1),
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		RunE: func(cmd *cobra.Command, args []string) error {
			pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
			if err != nil {
				return err
			}
			target, err := cli.target(targetOptions{zone: zoneArg, logLevel: logLevelArg})
			if err != nil {
				return err
			}
			opts := cli.createDeploymentOptions(pkg, target)

			var result vespa.PrepareResult
			err = cli.spinner(cli.Stderr, "Uploading application package ...", func() error {
				result, err = vespa.Deploy(opts)
				return err
			})
			if err != nil {
				return err
			}

			log.Println()
			if opts.IsCloud() {
				cli.printSuccess("Triggered deployment of ", color.CyanString(pkg.Path), " with run ID ", color.CyanString(strconv.FormatInt(result.ID, 10)))
			} else {
				cli.printSuccess("Deployed ", color.CyanString(pkg.Path))
				printPrepareLog(cli.Stderr, result)
			}
			if opts.IsCloud() {
				log.Printf("\nUse %s for deployment status, or follow this deployment at", color.CyanString("vespa status"))
				log.Print(color.CyanString(fmt.Sprintf("%s/tenant/%s/application/%s/dev/instance/%s/job/%s-%s/run/%d",
					opts.Target.Deployment().System.ConsoleURL,
					opts.Target.Deployment().Application.Tenant, opts.Target.Deployment().Application.Application, opts.Target.Deployment().Application.Instance,
					opts.Target.Deployment().Zone.Environment, opts.Target.Deployment().Zone.Region,
					result.ID)))
			}
			return waitForQueryService(cli, result.ID)
		},
	}
	cmd.PersistentFlags().StringVarP(&zoneArg, "zone", "z", "", "The zone to use for deployment. This defaults to a dev zone")
	cmd.PersistentFlags().StringVarP(&logLevelArg, "log-level", "l", "error", `Log level for Vespa logs. Must be "error", "warning", "info" or "debug"`)
	return cmd
}

func newPrepareCmd(cli *CLI) *cobra.Command {
	return &cobra.Command{
		Use:               "prepare application-directory",
		Short:             "Prepare an application package for activation",
		Args:              cobra.MaximumNArgs(1),
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		RunE: func(cmd *cobra.Command, args []string) error {
			pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
			if err != nil {
				return fmt.Errorf("could not find application package: %w", err)
			}
			target, err := cli.target(targetOptions{})
			if err != nil {
				return err
			}
			opts := cli.createDeploymentOptions(pkg, target)
			var result vespa.PrepareResult
			err = cli.spinner(cli.Stderr, "Uploading application package ...", func() error {
				result, err = vespa.Prepare(opts)
				return err
			})
			if err != nil {
				return err
			}
			if err := cli.config.writeSessionID(vespa.DefaultApplication, result.ID); err != nil {
				return fmt.Errorf("could not write session id: %w", err)
			}
			cli.printSuccess("Prepared ", color.CyanString(pkg.Path), " with session ", result.ID)
			return nil
		},
	}
}

func newActivateCmd(cli *CLI) *cobra.Command {
	return &cobra.Command{
		Use:               "activate",
		Short:             "Activate (deploy) a previously prepared application package",
		Args:              cobra.MaximumNArgs(1),
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		RunE: func(cmd *cobra.Command, args []string) error {
			pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
			if err != nil {
				return fmt.Errorf("could not find application package: %w", err)
			}
			sessionID, err := cli.config.readSessionID(vespa.DefaultApplication)
			if err != nil {
				return fmt.Errorf("could not read session id: %w", err)
			}
			target, err := cli.target(targetOptions{})
			if err != nil {
				return err
			}
			opts := cli.createDeploymentOptions(pkg, target)
			err = vespa.Activate(sessionID, opts)
			if err != nil {
				return err
			}
			cli.printSuccess("Activated ", color.CyanString(pkg.Path), " with session ", sessionID)
			return waitForQueryService(cli, sessionID)
		},
	}
}

func waitForQueryService(cli *CLI, sessionOrRunID int64) error {
	if cli.flags.waitSecs > 0 {
		log.Println()
		_, err := cli.service(vespa.QueryService, sessionOrRunID, "")
		return err
	}
	return nil
}

func printPrepareLog(stderr io.Writer, result vespa.PrepareResult) {
	for _, entry := range result.LogLines {
		level := entry.Level
		switch level {
		case "ERROR":
			level = color.RedString(level)
		case "WARNING":
			level = color.YellowString(level)
		}
		fmt.Fprintf(stderr, "%s %s", level, entry.Message)
	}
}
