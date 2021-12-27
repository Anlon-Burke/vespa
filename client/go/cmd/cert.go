// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa cert command
// Author: mpolden
package cmd

import (
	"fmt"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
	"os"
	"path/filepath"
)

var overwriteCertificate bool

func init() {
	certCmd.Flags().BoolVarP(&overwriteCertificate, "force", "f", false, "Force overwrite of existing certificate and private key")
	certCmd.MarkPersistentFlagRequired(applicationFlag)
}

func certExample() string {
	if vespa.Auth0AccessTokenEnabled() {
		return "$ vespa auth cert -a my-tenant.my-app.my-instance"
	} else {
		return "$ vespa cert -a my-tenant.my-app.my-instance"
	}
}

var certCmd = &cobra.Command{
	Use:               "cert",
	Short:             "Create a new private key and self-signed certificate for Vespa Cloud deployment",
	Example:           certExample(),
	DisableAutoGenTag: true,
	Args:              cobra.MaximumNArgs(1),
	Run:               doCert,
}

var deprecatedCertCmd = &cobra.Command{
	Use:               "cert",
	Short:             "Create a new private key and self-signed certificate for Vespa Cloud deployment",
	Example:           "$ vespa cert -a my-tenant.my-app.my-instance",
	DisableAutoGenTag: true,
	Args:              cobra.MaximumNArgs(1),
	Deprecated:        "use 'vespa auth cert' instead",
	Hidden:            true,
	Run:               doCert,
}

func doCert(_ *cobra.Command, args []string) {
	app := getApplication()
	pkg, err := vespa.FindApplicationPackage(applicationSource(args), false)
	if err != nil {
		fatalErr(err)
		return
	}
	cfg, err := LoadConfig()
	if err != nil {
		fatalErr(err)
		return
	}
	privateKeyFile, err := cfg.PrivateKeyPath(app)
	if err != nil {
		fatalErr(err)
		return
	}
	certificateFile, err := cfg.CertificatePath(app)
	if err != nil {
		fatalErr(err)
		return
	}

	if !overwriteCertificate {
		hint := "Use -f flag to force overwriting"
		if pkg.HasCertificate() {
			fatalErrHint(fmt.Errorf("Application package %s already contains a certificate", pkg.Path), hint)
			return
		}
		if util.PathExists(privateKeyFile) {
			fatalErrHint(fmt.Errorf("Private key %s already exists", color.Cyan(privateKeyFile)), hint)
			return
		}
		if util.PathExists(certificateFile) {
			fatalErrHint(fmt.Errorf("Certificate %s already exists", color.Cyan(certificateFile)), hint)
			return
		}
	}
	if pkg.IsZip() {
		var msg string
		if vespa.Auth0AccessTokenEnabled() {
			msg = "Try running 'mvn clean' before 'vespa auth cert', and then 'mvn package'"
		} else {
			msg = "Try running 'mvn clean' before 'vespa cert', and then 'mvn package'"
		}
		fatalErrHint(fmt.Errorf("Cannot add certificate to compressed application package %s", pkg.Path),
			msg)
		return
	}

	keyPair, err := vespa.CreateKeyPair()
	if err != nil {
		fatalErr(err, "Could not create key pair")
		return
	}
	pkgCertificateFile := filepath.Join(pkg.Path, "security", "clients.pem")
	if err := os.MkdirAll(filepath.Dir(pkgCertificateFile), 0755); err != nil {
		fatalErr(err, "Could not create security directory")
		return
	}
	if err := keyPair.WriteCertificateFile(pkgCertificateFile, overwriteCertificate); err != nil {
		fatalErr(err, "Could not write certificate")
		return
	}
	if err := keyPair.WriteCertificateFile(certificateFile, overwriteCertificate); err != nil {
		fatalErr(err, "Could not write certificate")
		return
	}
	if err := keyPair.WritePrivateKeyFile(privateKeyFile, overwriteCertificate); err != nil {
		fatalErr(err, "Could not write private key")
		return
	}
	printSuccess("Certificate written to ", color.Cyan(pkgCertificateFile))
	printSuccess("Certificate written to ", color.Cyan(certificateFile))
	printSuccess("Private key written to ", color.Cyan(privateKeyFile))
}
