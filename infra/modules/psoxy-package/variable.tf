
variable "path_to_psoxy_java" {
  type        = string
  description = "relative path from working directory (from which you call this module) to java/ folder within your checkout of the Psoxy repo"
}

variable "implementation" {
  type        = string
  description = "reference to implementation to build (subdirectory of java/impl/)"
  default     = "aws"
}

