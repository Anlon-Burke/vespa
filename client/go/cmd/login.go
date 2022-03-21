package cmd

import (
	"fmt"
	"log"
	"os"
	"time"

	"github.com/pkg/browser"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/auth"
	"github.com/vespa-engine/vespa/client/go/auth/auth0"
)

// newLoginCmd runs the login flow guiding the user through the process
// by showing the login instructions, opening the browser.
// Use `expired` to run the login from other commands setup:
// this will only affect the messages.
func newLoginCmd(cli *CLI) *cobra.Command {
	return &cobra.Command{
		Use:               "login",
		Args:              cobra.NoArgs,
		Short:             "Authenticate the Vespa CLI",
		Example:           "$ vespa auth login",
		DisableAutoGenTag: true,
		SilenceUsage:      true,
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx := cmd.Context()
			targetType, err := cli.config.targetType()
			if err != nil {
				return err
			}
			system, err := cli.system(targetType)
			if err != nil {
				return err
			}
			a, err := auth0.GetAuth0(cli.config.authConfigPath(), system.Name, system.URL)
			if err != nil {
				return err
			}
			state, err := a.Authenticator.Start(ctx)
			if err != nil {
				return fmt.Errorf("could not start the authentication process: %w", err)
			}

			log.Printf("Your Device Confirmation code is: %s\n\n", state.UserCode)

			log.Println("If you prefer, you can open the URL directly for verification")
			log.Printf("Your Verification URL: %s\n\n", state.VerificationURI)

			log.Println("Press Enter to open the browser to log in or ^C to quit...")
			fmt.Scanln()

			err = browser.OpenURL(state.VerificationURI)

			if err != nil {
				log.Printf("Couldn't open the URL, please do it manually: %s.", state.VerificationURI)
			}

			var res auth.Result
			err = cli.spinner(os.Stderr, "Waiting for login to complete in browser ...", func() error {
				res, err = a.Authenticator.Wait(ctx, state)
				return err
			})

			if err != nil {
				return fmt.Errorf("login error: %w", err)
			}

			log.Print("\n")
			log.Println("Successfully logged in.")
			log.Print("\n")

			// store the refresh token
			secretsStore := &auth.Keyring{}
			err = secretsStore.Set(auth.SecretsNamespace, system.Name, res.RefreshToken)
			if err != nil {
				// log the error but move on
				log.Println("Could not store the refresh token locally, please expect to login again once your access token expired.")
			}

			s := auth0.System{
				Name:        system.Name,
				AccessToken: res.AccessToken,
				ExpiresAt:   time.Now().Add(time.Duration(res.ExpiresIn) * time.Second),
				Scopes:      auth.RequiredScopes(),
			}
			err = a.AddSystem(&s)
			if err != nil {
				return fmt.Errorf("could not add system to config: %w", err)
			}
			return err
		},
	}
}
