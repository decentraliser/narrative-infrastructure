pipelineJob("cloudflare-enable-development-mode") {
  description("Enables Cloudflare development mode to prevent any caching during a deploy.")
  keepDependencies(false)
  parameters {
    stringParam("GIT_BRANCH_ORIGIN", "origin/master", "The Git branch to use. Defaults to master.")
    choiceParam('ZONE', ['narrative.org'], 'The Cloudflare zone.')
    choiceParam('SLACK_NOTIFY', ['yes', 'no'], 'Whether or not to notify the #jenkins channel in Slack.')
  }
  definition {
    cpsScm {
      scm {
        git {
          remote {
            github("NarrativeCompany/narrative-infrastructure", "ssh")
            credentials("24489ad6-f93a-4682-8ac3-cdeb6de18c71")
          }
          branch("\$GIT_BRANCH_ORIGIN")
        }
      }
      scriptPath("jenkins/cloudflare-enable-development-mode.groovy")
    }
  }
  disabled(false)
}
