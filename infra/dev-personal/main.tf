terraform {
  required_providers {
    google = {
      version = ">= 3.74, <= 4.0"
    }
  }

  # if you leave this as local, you should backup/commit your TF state files
  backend "local" {
  }
}

# NOTE: if you don't have perms to provision a GCP project in your billing account, you can have
# someone else create one and than import it:
#  `terraform import google_project.psoxy-project your-psoxy-project-id`
# either way, we recommend the project be used exclusively to host psoxy instances corresponding to
# a single worklytics account
resource "google_project" "psoxy-project" {
  name            = "Psoxy - ${var.environment_name}"
  project_id      = var.project_id
  folder_id       = var.folder_id
  billing_account = var.billing_account_id
}

module "psoxy-gcp" {
  source = "../modules/gcp"

  project_id          = google_project.psoxy-project.project_id
  invoker_sa_emails   = var.worklytics_sa_emails

  depends_on = [
    google_project.psoxy-project
  ]
}

module "gmail-connector" {
  source = "../modules/google-workspace-dwd-connection"

  project_id                   = var.project_id
  connector_service_account_id = "psoxy-gmail-dwd"
  display_name                 = "Psoxy Connector - GMail${var.connector_display_name_suffix}"
  apis_consumed                = [
    "gmail.googleapis.com"
  ]
  oauth_scopes_needed          = [
    "https://www.googleapis.com/auth/gmail.metadata"
  ]

  depends_on = [
    module.psoxy-gcp
  ]
}

# setup gmail to auth using a secret (not specific to Cloud Function)
module "gmail-connector-auth" {
  source = "../modules/gcp-sa-auth-key-secret-manager"

  secret_project     = var.project_id
  service_account_id = module.gmail-connector.service_account_id

  # TODO: recommend migrate this to `PSOXY_{{function_name}}_SERVICE_ACCOUNT_KEY`
  secret_id          = "PSOXY_SERVICE_ACCOUNT_KEY_gmail"
}

# for local dev, write the key to flat file on your machine (shouldn't do this for prod configs)
resource "local_file" "gmail-connector-sa-key" {
  filename = "gmail-connector-sa-key.json"
  content_base64 = module.gmail-connector-auth.key_value
}

module "psoxy-gmail" {
  source = "../modules/gcp-psoxy-cloud-function"

  project_id            = var.project_id
  function_name         = "psoxy-gmail"
  source_kind           = "gmail"
  service_account_email = module.gmail-connector.service_account_email

  secret_bindings = {
    PSOXY_SALT = {
      secret_name    = module.psoxy-gcp.salt_secret_name
      version_number = module.psoxy-gcp.salt_secret_version_number
    },
    SERVICE_ACCOUNT_KEY = {
      secret_name    = module.gmail-connector-auth.key_secret_name
      version_number = module.gmail-connector-auth.key_secret_version_number
    }
  }
}

module "worklytics-psoxy-connection-gmail" {
  source = "../modules/worklytics-psoxy-connection"

  psoxy_endpoint_url = module.psoxy-gmail.cloud_function_url
  display_name       = "GMail via Psoxy"
}

module "google-chat-connector" {
  source = "../modules/google-workspace-dwd-connection"

  project_id                   = var.project_id
  connector_service_account_id = "psoxy-google-chat-dwd"
  display_name                 = "Psoxy Connector - Google Chat${var.connector_display_name_suffix}"
  apis_consumed                = [
    "admin.googleapis.com"
  ]
  oauth_scopes_needed          = [
    "https://www.googleapis.com/auth/admin.reports.audit.readonly"
  ]
  depends_on = [
    module.psoxy-gcp
  ]
}

module "google-chat-connector-auth" {
  source = "../modules/gcp-sa-auth-key-secret-manager"

  secret_project     = var.project_id
  service_account_id = module.google-chat-connector.service_account_id
  # TODO: recommend migrate this to `PSOXY_{{function_name}}_SERVICE_ACCOUNT_KEY`
  secret_id          = "PSOXY_SERVICE_ACCOUNT_KEY_google-chat"
}

module "psoxy-google-chat" {
  source = "../modules/gcp-psoxy-cloud-function"

  project_id            = var.project_id
  function_name         = "psoxy-google-chat"
  source_kind           = "google-chat"
  service_account_email = module.google-chat-connector.service_account_email

  secret_bindings       = {
    PSOXY_SALT = {
      secret_name    = module.psoxy-gcp.salt_secret_name
      version_number = module.psoxy-gcp.salt_secret_version_number
    },
    SERVICE_ACCOUNT_KEY = {
      secret_name    = module.google-chat-connector-auth.key_secret_name
      version_number = module.google-chat-connector-auth.key_secret_version_number
    }
  }
}

module "worklytics-psoxy-connection-google-chat" {
  source = "../modules/worklytics-psoxy-connection"

  psoxy_endpoint_url = module.psoxy-google-chat.cloud_function_url
  display_name       = "Google Chat via Psoxy"
}

# BEGIN SLACK

locals {
  slack_function_name   = "psoxy-slack-discovery-api"
}

resource "google_service_account" "slack_connector_sa" {
  project = var.project_id
  account_id   = local.slack_function_name
  display_name = "Psoxy Connector - Slack{var.connector_display_name_suffix}"
}

# creates the secret, without versions.
module "slack-discovery-api-auth" {
  source   = "../modules/gcp-oauth-long-access-strategy"
  project_id              = var.project_id
  function_name           = local.slack_function_name
  token_adder_user_emails = []
}

module "psoxy-slack-discovery-api" {
  source = "../modules/gcp-psoxy-cloud-function"

  project_id            = var.project_id
  function_name         = local.slack_function_name
  source_kind           = "slack"
  service_account_email   = google_service_account.slack_connector_sa.email

  secret_bindings = {
    PSOXY_SALT   = {
      secret_name    = module.psoxy-gcp.salt_secret_name
      version_number = module.psoxy-gcp.salt_secret_version_number
    },
    ACCESS_TOKEN = {
      secret_name    = module.slack-discovery-api-auth.access_token_secret_name
      # in case of long lived tokens we want latest version always
      version_number = "latest"
    }
  }
}

# END SLACK

# BEGIN ZOOM

locals {
  zoom_function_name   = "psoxy-zoom"
}

resource "google_service_account" "zoom_connector_sa" {
  project = var.project_id
  account_id   = local.zoom_function_name
  display_name = "Psoxy Connector - Zoom{var.connector_display_name_suffix}"
}

# creates the secret, without versions.
module "zoom-api-auth" {
  source   = "../modules/gcp-oauth-long-access-strategy"
  project_id              = var.project_id
  function_name           = local.zoom_function_name
  token_adder_user_emails = []
}

module "psoxy-zoom-api" {
  source = "../modules/gcp-psoxy-cloud-function"

  project_id            = var.project_id
  function_name         = local.zoom_function_name
  source_kind           = "zoom"
  service_account_email   = google_service_account.zoom_connector_sa.email

  secret_bindings = {
    PSOXY_SALT   = {
      secret_name    = module.psoxy-gcp.salt_secret_name
      version_number = module.psoxy-gcp.salt_secret_version_number
    },
    ACCESS_TOKEN = {
      secret_name    = module.zoom-api-auth.access_token_secret_name
      # in case of long lived tokens we want latest version always
      version_number = "latest"
    }
  }
}

# current convention is that the Cloud Functions for each of these run as these SAs, but there's
# really no need to; consider splitting (eg, one SA for each Cloud Function; one SA for the
# OAuth client)

output "gmail_connector_sa_email" {
  value = module.gmail-connector.service_account_email
}

output "google_chat_connector_sa_email" {
  value = module.google-chat-connector.service_account_email
}
