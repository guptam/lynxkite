from utils.emr_lib import EMRLib
import os


class Ecosystem:

  def __init__(self, cluster_config, lynxkite_config):
    self.cluster_config = cluster_config
    self.lynxkite_config = lynxkite_config
    self.cluster = None
    self.instances = []
    self.jdbc_url = ''

  def launch_cluster(self):
    print('Launching EMR cluster.')
    # Create an EMR cluster.
    conf = self.cluster_config
    lib = EMRLib(
        ec2_key_file=conf['ec2_key_file'],
        ec2_key_name=conf['ec2_key_name'],
        region=conf['emr_region'])
    self.cluster = lib.create_or_connect_to_emr_cluster(
        name=conf['cluster_name'],
        log_uri=conf['emr_log_uri'],
        instance_count=conf['emr_instance_count'],
        hdfs_replication=conf['hdfs_replication'])
    self.instances = [self.cluster]
    # Spin up a mysql RDS instance only if requested.
    if conf['with_rds']:
      mysql_instance = lib.create_or_connect_to_rds_instance(
          name=conf['cluster_name'] + '-mysql')
      # Wait for startup of both.
      self.instances = self.instances + [mysql_instance]
      lib.wait_for_services(self.instances)
      mysql_address = mysql_instance.get_address()
      self.jdbc_url = 'jdbc:mysql://{mysql_address}/db?user=root&password=rootroot'.format(
          mysql_address=mysql_address)
    else:
      lib.wait_for_services(self.instances)

  def start(self):
    print('Starting LynxKite on EMR cluster.')
    conf = self.cluster_config
    lk_conf = self.lynxkite_config
    if lk_conf['lynx_version']:
      self.upload_installer_script(
          lk_conf['biggraph_releases_dir'],
          lk_conf['lynx_version'])
    self.upload_tasks()
    self.upload_tools()
    self.download_and_unpack_release(
        lk_conf['lynx_release_dir'],
        lk_conf['lynx_version'])
    self.install_native()
    self.config_and_prepare_native(
        lk_conf['s3_data_dir'],
        conf['emr_instance_count'])
    self.config_aws_s3_native()
    self.start_monitoring_on_extra_nodes_native(conf['ec2_key_file'])
    self.start_supervisor_native()

  def run_tests(self, test_config):
    conf = self.cluster_config
    lk_conf = self.lynxkite_config
    print('Running tests on EMR cluster.')
    self.start_tests_native(
        self.jdbc_url,
        lk_conf['task_module'],
        lk_conf['task'],
        test_config['dataset'])
    print('Tests are now running in the background. Waiting for results.')
    self.cluster.fetch_output()
    res_dir = test_config['results_local_dir']
    res_name = test_config['results_name']
    if not os.path.exists(res_dir):
      os.makedirs(res_dir)
    self.cluster.rsync_down(
        '/home/hadoop/test_results.txt',
        res_dir + res_name)
    self.upload_perf_logs_to_gcloud(conf['cluster_name'])

  def cleanup(self):
    lk_conf = self.lynxkite_config
    print('Downloading logs and stopping EMR cluster.')
    if lk_conf['log_dir']:
      self.download_logs_native(lk_conf['log_dir'])
    self.shut_down_instances()

  ###
  ### ecosystem launch methods ###
  ###

  def upload_installer_script(self, releases_dir, lynx_version):
    self.cluster.rsync_up(
        src='{dir}/download-lynx-{version}.sh'.format(
            dir=releases_dir,
            version=lynx_version),
        dst='/mnt/')

  def upload_tasks(self):
    ecosystem_task_dir = '/mnt/lynx/luigi_tasks/test_tasks'
    self.cluster.ssh('mkdir -p ' + ecosystem_task_dir)
    self.cluster.rsync_up('ecosystem/tests/', ecosystem_task_dir)
    self.cluster.ssh('''
        set -x
        cd /mnt/lynx/luigi_tasks/test_tasks
        mv test_runner.py /mnt/lynx/luigi_tasks
        ''')

  def upload_tools(self):
    target_dir = '/mnt/lynx/tools'
    self.cluster.ssh('mkdir -p ' + target_dir)
    self.cluster.rsync_up('ecosystem/native/tools/', target_dir)
    self.cluster.rsync_up('tools/performance_collection/', target_dir)

  def download_and_unpack_release(self, lynx_release_dir, lynx_version):
    if lynx_version:
      self.cluster.ssh('''
        set -x
        cd /mnt
        if [ ! -f "./lynx-{version}.tgz" ]; then
          ./download-lynx-{version}.sh
          mkdir -p lynx
          tar xfz lynx-{version}.tgz -C lynx --strip-components 1
        fi
        '''.format(version=lynx_version))
    else:
      self.cluster.rsync_up(lynx_release_dir + '/', '/mnt/lynx')

  def install_native(self):
    self.cluster.rsync_up('python_requirements.txt', '/mnt/lynx')
    self.cluster.ssh('''
    set -x
    cd /mnt/lynx
    sudo yum install -y python34-pip mysql-server gcc libffi-devel
    # Removes the given and following lines so only the necessary modules will be installed.
    sed -i -n '/# Dependencies for developing and testing/q;p'  python_requirements.txt
    sudo pip-3.4 install --upgrade -r python_requirements.txt
    sudo pip-2.6 install --upgrade requests[security] supervisor
    # mysql setup
    sudo service mysqld start
    mysqladmin  -u root password 'root' || true  # (May be set already.)
    # This mysql database is used for many things, including the testing of JDBC tasks.
    # For that purpose access needs to be granted for all executors.
    mysql -uroot -proot -e "GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' IDENTIFIED BY 'root'"
    ''')

  def config_and_prepare_native(self, s3_data_dir, emr_instance_count):
    hdfs_path = 'hdfs://$HOSTNAME:8020/user/$USER/lynxkite/'
    if s3_data_dir:
      data_dir_config = '''
        export KITE_DATA_DIR={}
        export KITE_EPHEMERAL_DATA_DIR={}
      '''.format(s3_data_dir, hdfs_path)
    else:
      data_dir_config = '''
        export KITE_DATA_DIR={}
      '''.format(hdfs_path)
    self.cluster.ssh('''
      cd /mnt/lynx
      echo 'Setting up environment variables.'
      # Removes the given and following lines so config/central does not grow constantly.
      sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  config/central
      cat >>config/central <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
        export KITE_INSTANCE=ecosystem-test
        export KITE_MASTER_MEMORY_MB=8000
        export NUM_EXECUTORS={num_executors}
        export EXECUTOR_MEMORY=18g
        export NUM_CORES_PER_EXECUTOR=8
        # port differs from the one used in central/config
        export HDFS_ROOT=hdfs://$HOSTNAME:8020/user/$USER
        {data_dir_config}
        export LYNXKITE_ADDRESS=https://localhost:$KITE_HTTPS_PORT/
        export PYTHONPATH=/mnt/lynx/apps/remote_api/python/:/mnt/lynx/luigi_tasks
        export HADOOP_CONF_DIR=/etc/hadoop/conf
        export YARN_CONF_DIR=$HADOOP_CONF_DIR
        export LYNX=/mnt/lynx
        #for tests with mysql server on master
        export DATA_DB=jdbc:mysql://$HOSTNAME:3306/'db?user=root&password=root&rewriteBatchedStatements=true'
        export KITE_INTERNAL_WATCHDOG_TIMEOUT_SECONDS=7200
EOF
      echo 'Creating hdfs directory.'
      source config/central
      hdfs dfs -mkdir -p $KITE_DATA_DIR/table_files
      echo 'Creating tasks_data directory.'
      # TODO: Find a more sane directory.
      sudo mkdir -p /tasks_data
      sudo chmod a+rwx /tasks_data
    '''.format(num_executors=emr_instance_count - 1, data_dir_config=data_dir_config))

  def config_aws_s3_native(self):
    self.cluster.ssh('''
      cd /mnt/lynx
      echo 'Setting s3 prefix.'
      sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  config/prefix_definitions.txt
      cat >>config/prefix_definitions.txt <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
  S3="s3://"
EOF
      echo 'Setting AWS CLASSPATH.'
      if [ -f spark/conf/spark-env.sh ]; then
        sed -i -n '/# ---- the below lines were added by test_ecosystem.py ----/q;p'  spark/conf/spark-env.sh
      fi
      cat >>spark/conf/spark-env.sh <<'EOF'
# ---- the below lines were added by test_ecosystem.py ----
  AWS_CLASSPATH1=$(find /usr/share/aws/emr/emrfs/lib -name "*.jar" | tr '\\n' ':')
  AWS_CLASSPATH2=$(find /usr/share/aws/aws-java-sdk -name "*.jar" | tr '\\n' ':')
  AWS_CLASSPATH3=$(find /usr/share/aws/emr/instance-controller/lib -name "*.jar" | tr '\\n' ':')
  AWS_CLASSPATH_ALL=$AWS_CLASSPATH1$AWS_CLASSPATH2$AWS_CLASSPATH3
  export SPARK_DIST_CLASSPATH=$SPARK_DIST_CLASSPATH:${AWS_CLASSPATH_ALL::-1}
EOF
      chmod a+x spark/conf/spark-env.sh
    ''')

  def start_monitoring_on_extra_nodes_native(self, keyfile):
    cluster_keyfile = 'cluster_key.pem'
    self.cluster.rsync_up(src=keyfile, dst='/home/hadoop/.ssh/' + cluster_keyfile)
    ssh_options = '''-o UserKnownHostsFile=/dev/null \
      -o CheckHostIP=no \
      -o StrictHostKeyChecking=no \
      -i /home/hadoop/.ssh/{keyfile}'''.format(keyfile=cluster_keyfile)

    self.cluster.ssh('''
      yarn node -list -all | grep RUNNING | cut -d':' -f 1 > nodes.txt
      ''')

    self.cluster.ssh('''
      for node in `cat nodes.txt`; do
        scp {options} \
          /mnt/lynx/other_nodes/other_nodes.tgz \
          hadoop@${{node}}:/home/hadoop/other_nodes.tgz
        ssh {options} hadoop@${{node}} tar xf other_nodes.tgz
        ssh {options} hadoop@${{node}} "sh -c 'nohup ./run.sh >run.stdout 2> run.stderr &'"
      done'''.format(options=ssh_options))

    # Uncomment services in configs
    self.cluster.ssh('''
      /mnt/lynx/tools/uncomment_config.sh /mnt/lynx/config/monitoring/prometheus.yml
      /mnt/lynx/tools/uncomment_config.sh /mnt/lynx/config/supervisord.conf
      ''')

    self.cluster.ssh('''
      /mnt/lynx/scripts/service_explorer.sh
    ''')

  def start_supervisor_native(self):
    self.cluster.ssh_nohup('''
      set -x
      source /mnt/lynx/config/central
      /usr/local/bin/supervisord -c config/supervisord.conf
      ''')

  ###
  ### test runner methods ###
  ###

  def start_tests_native(self, jdbc_url, task_module, task, dataset):
    '''Start running the tests in the background.'''
    self.cluster.ssh_nohup('''
        source /mnt/lynx/config/central
        echo 'cleaning up previous test data'
        cd /mnt/lynx
        hadoop fs -rm -r /user/hadoop/lynxkite
        sudo rm -Rf metadata/lynxkite/*
        supervisorctl restart lynxkite
        ./reload_luigi_tasks.sh
        echo 'Waiting for the ecosystem to start...'
        rm -f /tasks_data/smoke_test_marker.txt
        rm -Rf /tmp/luigi/
        touch /mnt/lynx/luigi_tasks/test_tasks/__init__.py
        while [[ $(cat /tasks_data/smoke_test_marker.txt 2>/dev/null) != "done" ]]; do
          luigi --module test_tasks.smoke_test SmokeTest
          sleep 1
        done
        echo 'Ecosystem started.'
        JDBC_URL='{jdbc_url}' DATASET={dataset} \
        python3 /mnt/lynx/luigi_tasks/test_runner.py \
            --module {luigi_module} \
            --task {luigi_task} \
            --result_file /home/hadoop/test_results.txt
    '''.format(
        luigi_module=task_module,
        luigi_task=task,
        jdbc_url=jdbc_url,
        dataset=dataset,
    ))

  def upload_perf_logs_to_gcloud(self, cluster_name):
    print('Uploading performance logs to gcloud.')
    instance_name = 'emr-' + cluster_name
    self.cluster.ssh('''
      cd /mnt/lynx
      tools/multi_upload.sh 0 apps/lynxkite/logs {i}
    '''.format(i=instance_name))

  ###
  ### cleanup methods ###
  ###

  def download_logs_native(self, log_dir):
    if not os.path.exists(log_dir):
      os.makedirs(log_dir)
    self.cluster.rsync_down('/mnt/lynx/logs/', log_dir)
    self.cluster.rsync_down('/mnt/lynx/apps/lynxkite/logs/', log_dir + '/lynxkite-logs/')

  def shut_down_instances(self):
    if self.prompt_delete():
      print('Shutting down instances.')
      self.cluster.turn_termination_protection_off()
      for instance in self.instances:
        instance.terminate()

  def prompt_delete(self):
    if self.cluster_config['rm']:
      return True
    print('Terminate instances? [y/N] ', end='')
    choice = input().lower()
    if choice == 'y':
      return True
    else:
      print('''Please don't forget to terminate the instances!''')
      return False