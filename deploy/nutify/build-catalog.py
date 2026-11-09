#!/usr/bin/env python3
"""Build the reviewed Nutify 0.3 operation/form catalog from its source and API catalog."""
import argparse,ast,hashlib,json,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--catalog',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
funcs={}
for f in (a.source/'core').rglob('*.py'):
 try:t=ast.parse(f.read_text())
 except (SyntaxError,UnicodeError):continue
 for n in ast.walk(t):
  if isinstance(n,ast.FunctionDef):funcs.setdefault(n.name,[]).append((str(f.relative_to(a.source)),n))
L={'target_id':'UPS 设备','name':'名称','ups_name':'UPS 标识','host':'服务器地址','port':'端口','username':'用户名','password':'密码','enabled':'启用','is_primary':'主设备','polling_interval':'采样间隔（秒）','retention_days':'数据保留天数','db_strategy':'存储策略','shard_granularity':'分片周期','separate_db_path':'独立数据库路径','nut_mode':'运行模式','server_name':'实例名称','monitoring_profile':'监控模式','timezone':'时区','currency':'币种','price_per_kwh':'电价（每千瓦时）','co2_factor':'碳排放系数','ups_realpower_nominal':'额定有功功率（W）','command':'控制命令','value':'值','content':'配置内容','wait_time':'等待时间（秒）','from_date':'开始时间','to_date':'结束时间','report_type':'报告类型','start_date':'开始日期','end_date':'结束日期','period':'统计周期','interval':'时间间隔','range':'时间范围','hours':'最近小时数','rows':'记录条数','page':'页码','page_size':'每页条数','type':'类型','level':'级别','lines':'日志行数','ids':'记录 ID 列表','event_ids':'事件 ID 列表','user_id':'用户 ID','action_id':'脚本 ID','event_id':'事件 ID','filename':'文件名','variable':'变量名称','service_name':'服务名称','is_admin':'管理员','role':'角色','permissions':'页面权限','options_tabs':'设置页权限','tabs':'设置页','pages':'页面','new_password':'新密码','confirm_password':'确认密码','display_name':'显示名称','mappings':'变量映射','replace':'替换已有映射','include_disabled':'包含停用设备','script_body':'脚本内容','trigger_event':'触发事件','battery_threshold':'电量阈值（%）','cooldown_seconds':'冷却时间（秒）','issuer':'身份提供商地址','client_id':'客户端 ID','client_secret':'客户端密钥','redirect_uri':'回调地址','scopes':'授权范围','username_claim':'用户名属性','groups_claim':'用户组属性','admin_groups':'管理员组','user_groups':'用户组','allow_all_users':'允许所有用户','allow_private_network':'允许私网提供商','provider_name':'提供商名称','button_label':'登录按钮文字','auto_redirect':'自动跳转','initial_access_token':'初始注册令牌','location_enabled':'启用位置信息','location':'位置名称','location_country':'国家','location_region':'省/州','location_city':'城市','location_postal_code':'邮编','location_address':'详细地址','location_latitude':'纬度','location_longitude':'经度','measured_power_metric_key':'实测功率变量','load_metric_key':'负载变量','nominal_power_metric_key':'额定功率变量','realpower_formula':'功率计算公式','power_calibration_factor':'功率校准系数','energy_formula':'能耗计算公式','cost_formula':'费用计算公式','co2_formula':'碳排放计算公式'}
T={'get_targets':'设备列表','get_target':'设备详情','create_target_route':'添加设备','update_target_route':'编辑设备','delete_target_route':'删除设备','test_target':'测试设备连接','toggle_target_route':'启用或停用设备','set_primary_route':'设为主设备','poll_now_route':'立即采样','overview_route':'多设备概览','history_route':'设备历史','dashboard_route':'设备运行数据','report_route':'设备汇总报告','multi_nut_state':'监控模式','renamer_catalog_route':'变量映射','save_renamer_mappings_route':'保存变量映射','get_ups_commands_api':'支持的控制命令','execute_ups_command_api':'执行控制命令','api_get_command_stats':'命令统计','api_get_command_logs':'命令历史','api_clear_command_logs':'清空命令历史','api_upsrw_list':'可写变量','api_upsrw_set':'修改变量','api_upsrw_history':'变量变更历史','api_upsrw_history_variable':'单变量历史','api_upsrw_clear_history':'清空变量历史','generate_report':'生成报告','get_nut_files':'配置文件','get_nut_config':'读取配置文件','update_nut_config':'保存配置文件','get_nut_docs':'配置文件说明','restart_nut':'重启 NUT 服务','get_nut_status':'NUT 服务状态','start_services':'启动 NUT 服务','stop_services':'停止 NUT 服务','restart_services':'重启 NUT 服务','get_logs':'NUT 服务日志','api_data_all':'实时快照','get_column_data':'单项监测值','get_dynamic_table':'历史采样数据','get_static_table':'设备静态信息','get_events_table_route':'事件列表','acknowledge_event_route':'确认事件','acknowledge_events_bulk':'批量确认事件','delete_event_route':'删除事件','delete_events_bulk':'批量删除事件','delete_all_events':'清空事件','api_backup_database':'下载数据库备份','api_database_stats':'数据库统计','api_optimize_database':'优化数据库','api_vacuum_database':'回收数据库空间','handle_get_logs':'应用日志','handle_clear_logs':'清空应用日志','download_logs':'下载日志','api_system_info_compat':'系统信息','system_stats':'系统资源','get_variables_settings':'计费与采样设置','save_variables_config':'保存计费设置','get_operations_settings':'计算公式','save_operations_settings':'保存计算公式','update_polling_interval':'修改采样间隔','get_initial_setup_options':'实例设置','update_initial_setup_options':'保存实例设置','restart_application':'重启 Nutify','list_script_actions':'自动化脚本','create_script_action':'添加自动化脚本','update_script_action':'编辑自动化脚本','delete_script_action':'删除自动化脚本','test_script_action':'执行脚本测试','api_admin_get_users':'用户列表','api_admin_create_user':'添加用户','api_admin_delete_user':'删除用户','api_admin_update_user_password':'修改用户密码','api_admin_update_user_role':'修改用户角色','api_admin_get_user_permissions':'用户页面权限','api_admin_update_user_permissions':'保存页面权限','api_admin_get_user_options_tabs':'用户设置权限','api_admin_update_user_options_tabs':'保存设置权限','oidc_admin_get':'单点登录设置','oidc_admin_save':'保存单点登录设置','oidc_admin_delete':'删除单点登录设置','oidc_admin_discover':'发现身份提供商','oidc_admin_dynamic_register':'注册单点登录客户端','oidc_admin_enabled':'启用或停用单点登录','get_ups_json':'导出 UPS 数据','get_available_drivers':'驱动目录','run_nut_scanner':'扫描 UPS','generate_config_preview':'预览接入配置','save_config':'应用接入配置','test_config':'测试接入配置','test_setup_target':'测试接入目标','validate_setup_location':'校验位置','location_suggestions':'位置建议','delete_config':'重置接入配置','check_status':'检查接入状态'}
L.update({'from_time':'开始时间（ISO 8601）','to_time':'结束时间（ISO 8601）','selected_date':'指定日期','selected_day':'指定日期','today_mode':'当日模式','detail_type':'明细粒度','days':'天数','metadata_only':'仅元数据','command_path':'NUT 客户端路径','connection_type':'连接类型','multi_topology':'设备拓扑','multi_targets':'设备列表','remote_host':'远程服务器','remote_port':'远程端口','remote_user':'远程用户名','remote_password':'远程密码','remote_ups_name':'远程 UPS 标识','ups_driver':'UPS 驱动','ups_port':'设备端口','ups_desc':'设备描述','listen_address':'监听地址','listen_port':'监听端口','monitor_username':'监控用户名','monitor_password':'监控密码','admin_user':'管理用户名','admin_password':'管理密码'})
EN={'nut_mode':['netclient','netserver','standalone'],'db_strategy':['shared','sharded','separate'],'shard_granularity':['month','day'],'monitoring_profile':['single','multi'],'trigger_event':['ONBATT','LOWBATT'],'report_type':['custom','daily','weekly','monthly'],'role':['administrator','user']}
NUM={'port','polling_interval','retention_days','target_id','user_id','action_id','event_id','wait_time','rows','hours','page','page_size','lines','ups_realpower_nominal','battery_threshold','cooldown_seconds','days','year','month','limit','offset'}
BOOL={'enabled','is_primary','is_admin','include_disabled','location_enabled','replace','allow_all_users','allow_private_network','auto_redirect','use_ssl','use_tls','force'}
def prop(k,default=None):
 typ='boolean' if k in BOOL or isinstance(default,bool) else 'integer' if k in NUM or isinstance(default,int) else 'number' if isinstance(default,float) or k in ['price_per_kwh','co2_factor','power_calibration_factor','location_latitude','location_longitude'] else 'array' if isinstance(default,list) or k in ['ids','event_ids','permissions','options_tabs','tabs','pages'] else 'object' if isinstance(default,dict) or k=='mappings' else 'string'
 s={'type':typ,'title':L.get(k,k)}
 if k in EN:s['enum']=EN[k]
 if default is not None and not isinstance(default,(dict,list)):s['default']=(str(default).lower()=='true') if typ=='boolean' and isinstance(default,str) else int(default) if typ=='integer' and isinstance(default,str) and default.isdigit() else default
 if typ=='array':s['items']={'type':'integer' if k in ['ids','event_ids'] else 'string'}
 if typ=='object':s['additionalProperties']={'type':'string'} if k=='mappings' else True
 if typ in ['number','integer'] and k not in ['location_latitude','location_longitude']:s['minimum']=0
 if k=='port':s['minimum']=1;s['maximum']=65535
 if k in ['content','script_body','config','preview']:s['format']='multiline'
 return s
def extract(fn):
 body={};query={}
 for n in ast.walk(fn):
  if isinstance(n,ast.Subscript) and isinstance(n.value,ast.Name) and n.value.id in ['data','payload','body'] and isinstance(n.slice,ast.Constant) and isinstance(n.slice.value,str):
   body.setdefault(n.slice.value,prop(n.slice.value))
  if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='get' and n.args and isinstance(n.args[0],ast.Constant) and isinstance(n.args[0].value,str):
   k=n.args[0].value;obj=ast.unparse(n.func.value)
   if len(k)>65 or not re.match(r'^[a-zA-Z_][\w.-]*$',k):continue
   try:default=ast.literal_eval(n.args[1]) if len(n.args)>1 else None
   except:default=None
   if obj=='request.args':query[k]=prop(k,default)
   elif obj in ['data','payload','request.json','body','config_data','settings_data','input_data']:body[k]=prop(k,default)
 return body,query
ALL=json.loads(a.catalog.read_text())['data'];ops=[];excluded=[]
def excluded_reason(r,g):
 path=r['path']
 if g in ['ntfy','telegram','webhook','nut_event'] or re.search(r'/settings/(mail|nutify|report|test-notification)|/report/(send|schedule)',path):return '通知与报告发送：用户要求不集成'
 if g in ['frontend','about'] or path in ['/api/multi-nut/active-target','/auth/api/status','/auth/api/oidc'] or (g=='auth' and not path.startswith('/auth/api/admin/')):return '登录与当前设备选择由 InfraPilot 提供；内部界面接口不转发'
 if path in ['/api/settings','/api/settings/<filename>'] or path.startswith('/api/log/') or (path.startswith('/api/options/') and not re.search('script-actions|options-from-initial-setup',path)):return '旧接口或通用配置入口；使用对应专用功能接口'
 if '/notifycmd-script' in path:return '通知发送脚本：用户要求不集成'
 return None
for g in ALL['groups']:
 for r in g['routes']:
  reason=excluded_reason(r,g['key'])
  if reason:excluded.append({'path':r['path'],'methods':r['methods'],'reason':reason});continue
  candidates=funcs.get(r['function_name'],[]);fn=next((n for f,n in candidates if r['module'].replace('.','/')+'.py'==f),candidates[0][1] if candidates else None)
  fields,query=extract(fn) if fn else ({},{})
  for method in r['methods']:
   path=r['path'];kind=g['key'];name=r['function_name'];body={} if method=='GET' else fields.copy()
   if '/multi-nut/targets' in path and method in ['POST','PUT'] and (path.endswith('/targets') or path.endswith('/test') or path.endswith('<int:target_id>')):
    body={k:prop(k) for k in ['name','ups_name','host','port','command_path','nut_mode','db_strategy','shard_granularity','separate_db_path','polling_interval','retention_days','enabled','is_primary','location_enabled','location','location_country','location_region','location_city','location_postal_code','location_address','location_latitude','location_longitude']}
   if 'script_action' in name and method in ['POST','PUT'] and not path.endswith('/test'):body={k:prop(k) for k in ['name','trigger_event','battery_threshold','cooldown_seconds','script_body','enabled']}
   if path=='/api/settings/operations' and method=='POST':body={k:prop(k) for k in ['measured_power_metric_key','load_metric_key','nominal_power_metric_key','realpower_formula','power_calibration_factor','energy_formula','cost_formula','co2_formula']}
   if path=='/api/multi-nut/renamer/mappings':body={k:prop(k) for k in ['mappings','replace']}
   if path=='/auth/api/admin/oidc' and method=='PUT' or path.endswith('/oidc/dynamic-register'):
    body={k:prop(k) for k in ['issuer','client_id','client_secret','redirect_uri','scopes','username_claim','groups_claim','admin_groups','user_groups','allow_all_users','allow_private_network','provider_name','button_label','auto_redirect']}
    if path.endswith('dynamic-register'):body['initial_access_token']=prop('initial_access_token')
   if path=='/api/settings/variables' and method=='POST':body={k:prop(k) for k in ['currency','price_per_kwh','co2_factor','timezone','ups_realpower_nominal']}
   if path=='/api/settings/polling-interval':body={'polling_interval':prop('polling_interval'),'update_both':{'type':'boolean','title':'同时更新 NUT 采样频率'}}
   if path=='/api/settings/log' and method=='POST':body={'log':{'type':'boolean','title':'记录日志'},'level':{'type':'string','enum':['DEBUG','INFO','WARNING','ERROR','CRITICAL'],'title':'日志级别'},'werkzeug':{'type':'boolean','title':'记录 HTTP 请求'}}
   if path.endswith('/oidc/enabled'):body={'enabled':prop('enabled')}
   if path.endswith('/permissions') and method=='POST':body={'permissions':{'type':'object','properties':{k:{'type':'boolean','title':k} for k in ['home','energy','power','battery','voltage','info','command','settings','events','options']},'additionalProperties':False}}
   if path.endswith('/options-tabs') and method=='POST':body={'options_tabs':{'type':'object','properties':{k:{'type':'boolean','title':k} for k in ['powerflow','options','database','log','advanced','renamer','operations','admin']},'additionalProperties':False}}
   if path.startswith('/nut_config/api/setup/'):
    target_fields=['name','ups_name','connection_type','host','port','nut_mode','monitor_username','monitor_password','local_driver','local_port','local_description','snmp_version','snmp_community','snmp_secLevel','snmp_secName','snmp_authProtocol','snmp_authPassword','snmp_privProtocol','snmp_privPassword','usb_vendorid','usb_productid','usb_serial','usb_vendor','usb_product','usb_bus','usb_device','usb_busport','timezone','currency','ups_realpower_nominal','location_enabled','location_country','location_region','location_city','location_postal_code','location_address','location_latitude','location_longitude']
    target_schema={'type':'object','properties':{k:prop(k) for k in target_fields},'required':['ups_name','connection_type'],'additionalProperties':False}
    target_schema['properties']['connection_type']['enum']=['remote_nut','local_usb_serial','local_network_driver']
    if 'multi_targets' in body:body['multi_targets']={'type':'array','title':'设备列表','items':target_schema}
    if 'additional_users' in body:body['additional_users']={'type':'array','title':'其他 NUT 用户','items':{'type':'object','properties':{k:prop(k) for k in ['username','password','role']},'additionalProperties':True}}
    if path.endswith('/test-target'):body=target_schema['properties'].copy()
    for k in body:
     if k.endswith('_conf') or k.endswith('.conf') or k in ['upsd_users','upsd.users','raw_ups_config']:body[k]['format']='multiline'
   # Explicit target scoping prevents one user's selection changing another session's UPS.
   scoped=kind in ['battery','energy','events','power','voltage','upscmd','upsrw','data','table','nut_history','upsmon','ups'] or '/settings/variables' in path or '/settings/operations' in path or '/options-from-initial-setup' in path or '/script-actions' in path or '/report/generate' in path or '/renamer/' in path
   query.pop('target_id',None);body.pop('target_id',None)
   if method=='GET' and path.startswith('/api/multi-nut/') and any(x in path for x in ['/overview','/report']):query.setdefault('hours',prop('hours',24))
   ident=method.lower()+'-'+name+'-'+hashlib.sha256(path.encode()).hexdigest()[:5]
   params={key:prop(key) for _,key in re.findall(r'<(?:(int):)?(\w+)>',path)}
   read=method=='GET' or path=='/api/report/generate'
   title=T.get(name,r['summary'] or name)
   if name=='update_log_setting':title='日志级别设置' if method=='GET' else '保存日志级别'
   if name.startswith('api_battery_'):title={'history':'电池历史','stats':'电池统计','metrics':'电池读数','has_hour_data':'采样可用性'}.get(name.removeprefix('api_battery_'),title)
   for prefix,word in [('api_power_','功率'),('api_voltage_','电压')]:
    if name.startswith(prefix):title=word+{'history':'历史','stats':'统计','metrics':'读数','outlet_groups':'插座分组','has_hour_data':'采样可用性'}.get(name.removeprefix(prefix),'数据')
   title={'get_voltage_metrics':'电压读数','get_energy_data_api':'能耗与费用','get_cost_trend_data':'费用趋势','get_available_years':'可查询年份','get_energy_detailed_data':'能耗明细','api_energy_has_hour_data':'采样可用性','nut_history':'NUT 事件历史','get_events':'UPS 事件','get_history':'UPS 历史','handle_acknowledge_event':'确认事件','reload_settings':'重新加载设置'}.get(name,title)
   if path=='/api/energy/detailed':
    query['detail_type']={'type':'string','title':'明细粒度','enum':['day','hour','minute'],'default':'day'}
   binary=path.endswith('/backup') or path.endswith('/download')
   ops.append({'id':ident,'group':kind,'title':title,'method':method,'path':path,'read':read,'scope':scoped,'binary':binary,'params':{'type':'object','properties':params,'required':list(params),'additionalProperties':False},'query':{'type':'object','properties':query,'additionalProperties':True,'required':['from_time','to_time','detail_type'] if path=='/api/energy/detailed' else []},'body':{'type':'object','properties':body,'additionalProperties':True,'required':[k for k in {'/api/upscmd/execute':['command'],'/api/upsrw/set':['name','value'],'/api/settings/variables':['currency','price_per_kwh','co2_factor'],'/api/settings/polling-interval':['polling_interval'],'/api/report/generate':['from_date','to_date','report_type'],'/api/multi-nut/targets':['name','ups_name','host'],'/auth/api/admin/users':['username','password','role']}.get(path,[]) if k in body]},'source':r['module']+'.'+name})
result={'version':'0.3.0','source':'https://github.com/DartSteven/Nutify/tree/943c5794773c4a39fb17462a0e4f4014a9c86e73','notifications':False,'operations':ops,'excluded':excluded}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Operations:',len(ops),'Explicit exclusions:',len(excluded))
