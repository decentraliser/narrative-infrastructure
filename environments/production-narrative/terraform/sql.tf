resource "google_sql_database_instance" "production-master" {
  name = "production-master"
  database_version = "${ var.db_version }"
  project = "${ var.project }"
  region = "${ var.region }"

  settings {
    maintenance_window = {}
    tier = "${ var.sql_machine_type }"
    database_flags = {
      name = "log_bin_trust_function_creators"
      value = "on"
    }
    database_flags = {
      name = "character_set_server"
      value = "utf8mb4"
    }
    disk_size = "100"
    disk_type = "PD_SSD"
    replication_type = "SYNCHRONOUS"

    backup_configuration {
      binary_log_enabled = true
      enabled = true
      start_time = "01:00"
    }
  }
}

resource "google_sql_database_instance" "production-failover" {
  name = "production-failover"
  master_instance_name = "${ google_sql_database_instance.production-master.name }"
  database_version = "${ var.db_version }"
  project = "${ var.project }"
  region = "${ var.region }"

  settings {
    tier = "${ var.sql_machine_type }"
    database_flags = {
      name = "log_bin_trust_function_creators"
      value = "on"
    }
    database_flags = {
      name = "character_set_server"
      value = "utf8mb4"
    }
    disk_size = "100"
    replication_type = "SYNCHRONOUS"
    crash_safe_replication = true
  }

  replica_configuration {
    # failover_target - (Optional) Specifies if the replica is the failover target. If the field is set to true the replica will be designated as a failover replica. If the master instance fails, the replica instance will be promoted as the new master instance.
    failover_target = true
  }
}

# resource "google_sql_user" "root" {
#   instance = "${ google_sql_database_instance.production-master.name }"
#   name     = "root"
#   password = "${ data.google_kms_secret.sql-root-password.plaintext }"
#   project = "${ var.project }"
# }
