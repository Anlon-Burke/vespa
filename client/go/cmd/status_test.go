// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// status command tests
// Author: bratseth

package cmd

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestStatusDeployCommand(t *testing.T) {
	assertDeployStatus("http://127.0.0.1:19071", []string{}, t)
}

func TestStatusDeployCommandWithURLTarget(t *testing.T) {
	assertDeployStatus("http://mydeploytarget:19071", []string{"-t", "http://mydeploytarget"}, t)
}

func TestStatusDeployCommandWithLocalTarget(t *testing.T) {
	assertDeployStatus("http://127.0.0.1:19071", []string{"-t", "local"}, t)
}

func TestStatusQueryCommand(t *testing.T) {
	assertQueryStatus("http://127.0.0.1:8080", []string{}, t)
}

func TestStatusQueryCommandWithUrlTarget(t *testing.T) {
	assertQueryStatus("http://mycontainertarget:8080", []string{"-t", "http://mycontainertarget"}, t)
}

func TestStatusQueryCommandWithLocalTarget(t *testing.T) {
	assertQueryStatus("http://127.0.0.1:8080", []string{"-t", "local"}, t)
}

func TestStatusDocumentCommandWithLocalTarget(t *testing.T) {
	assertDocumentStatus("http://127.0.0.1:8080", []string{"-t", "local"}, t)
}

func TestStatusErrorResponse(t *testing.T) {
	assertQueryStatusError("http://127.0.0.1:8080", []string{}, t)
}

func assertDeployStatus(target string, args []string, t *testing.T) {
	client := &mockHttpClient{}
	assert.Equal(t,
		"Deploy API at "+target+" is ready\n",
		executeCommand(t, client, []string{"status", "deploy"}, args),
		"vespa status config-server")
	assert.Equal(t, target+"/status.html", client.lastRequest.URL.String())
}

func assertQueryStatus(target string, args []string, t *testing.T) {
	client := &mockHttpClient{}
	assert.Equal(t,
		"Container (query API) at "+target+" is ready\n",
		executeCommand(t, client, []string{"status", "query"}, args),
		"vespa status container")
	assert.Equal(t, target+"/ApplicationStatus", client.lastRequest.URL.String())

	assert.Equal(t,
		"Container (query API) at "+target+" is ready\n",
		executeCommand(t, client, []string{"status"}, args),
		"vespa status (the default)")
	assert.Equal(t, target+"/ApplicationStatus", client.lastRequest.URL.String())
}

func assertDocumentStatus(target string, args []string, t *testing.T) {
	client := &mockHttpClient{}
	assert.Equal(t,
		"Container (document API) at "+target+" is ready\n",
		executeCommand(t, client, []string{"status", "document"}, args),
		"vespa status container")
	assert.Equal(t, target+"/ApplicationStatus", client.lastRequest.URL.String())
}

func assertQueryStatusError(target string, args []string, t *testing.T) {
	client := &mockHttpClient{}
	client.NextStatus(500)
	cmd := []string{"status", "container"}
	cmd = append(cmd, args...)
	_, outErr := execute(command{args: cmd}, t, client)
	assert.Equal(t,
		"Error: Container (query API) at "+target+" is not ready: status 500\n",
		outErr,
		"vespa status container")
}
