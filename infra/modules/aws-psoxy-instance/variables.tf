
variable "region" {
  type        = string
  description = "region into which to deploy function"
  default     = "us-east-1"
}

variable "function_name" {
  type        = string
  description = "name of function"
}

variable "aws_assume_role_arn" {
  type        = string
  description = "role arn"
}

variable "source_kind" {
  type        = string
  description = "kind of source (eg, 'gmail', 'google-chat', etc)"
}

variable "parameters" {
  # see https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter#attributes-reference
  type = list(object({
    name    = string
    arn     = string
    version = string
  }))
  description = "System Manager Parameters to expose to function"
}

variable "api_gateway" {
  type        = object({
    id = string
    arn = string
    api_endpoint = string
    execution_arn = string
  })
  description = "API gateway behind which proxy instance should sit"
}

variable "path_to_function_zip" {
  type        = string
  description = "path to zip archive of lambda bundle"
}

variable "function_zip_hash" {
  type        = string
  description = "hash of base64-encoded zipped lambda bundle"
}

variable "path_to_config" {
  type        = string
  description = "path to config file (usually someting in ../../configs/, eg configs/gdirectory.yaml"
}

variable "api_caller_role_arn" {
  type        = string
  description = "arn of role which can be assumed to all API"
}

variable "example_api_calls" {
  type        = list(string)
  description = "example endpoints that can be called via proxy"
}