import unittest
import lynx.kite
import json


class TestWorkspaceBuilder(unittest.TestCase):

  def test_one_box_ws(self):
    lk = lynx.kite.LynxKite()
    # Using explicit output name for test.
    state = lk.get_state_id(lk.createExampleGraph()['project'])
    project = lk.get_project(state)
    scalars = {s.title: lk.get_scalar(s.id) for s in project.scalars}
    self.assertEqual(scalars['!vertex_count'].double, 4.0)
    self.assertEqual(scalars['!edge_count'].double, 4.0)
    self.assertEqual(scalars['greeting'].string, 'Hello world! 😀 ')

  def test_numeric_box_parameter(self):
    lk = lynx.kite.LynxKite()
    s = lk.createVertices(size=6)
    res = lk.get_state_id(s)
    scalars = {s.title: lk.get_scalar(s.id) for s in lk.get_project(res).scalars}
    self.assertEqual(scalars['!vertex_count'].double, 6.0)

  def test_simple_chain(self):
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().computePagerank().sql('select page_rank from vertices')
    table_state = lk.get_state_id(state)
    table = lk.get_table(table_state)
    self.assertEqual(table.header[0].dataType, 'Double')
    self.assertEqual(table.header[0].name, 'page_rank')
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['1.80917', '1.80917', '0.19083', '0.19083'])

  def test_simple_sql_chain(self):
    lk = lynx.kite.LynxKite()
    state = (lk.createExampleGraph()
             .sql('select * from vertices where age < 30')
             .sql('select name from input where age > 2'))
    table_state = lk.get_state_id(state)
    table = lk.get_table(table_state)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Adam', 'Eve'])

  def test_multi_input(self):
    lk = lynx.kite.LynxKite()
    eg = lk.createExampleGraph()
    new_edges = eg.sql('select * from edges where edge_weight > 1')
    new_graph = lk.useTableAsEdges(
        eg, new_edges, attr='id', src='src_id', dst='dst_id')
    project = lk.get_project(lk.get_state_id(new_graph))
    scalars = {s.title: lk.get_scalar(s.id) for s in project.scalars}
    self.assertEqual(scalars['!vertex_count'].double, 4.0)
    self.assertEqual(scalars['!edge_count'].double, 3.0)

  def test_pedestrian_custom_box(self):
    lk = lynx.kite.LynxKite()
    i = lk.input(name='graph')
    o = i.sql('select name from vertices').output(name='vtable')
    ws = lynx.kite.Workspace('allvs', [o], [i])
    table_state = lk.get_state_id(ws(lk.createExampleGraph()))
    table = lk.get_table(table_state)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Adam', 'Eve', 'Bob', 'Isolated Joe'])

  def test_save_under_root(self):
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().sql('select name from vertices')
    ws = lynx.kite.Workspace('eg_names', [state])
    lk.remove_name('save_it_under_this_folder/eg_names', force=True)
    lk.run_workspace(ws, 'save_it_under_this_folder/')
    entries = lk.list_dir('save_it_under_this_folder')
    self.assertTrue('save_it_under_this_folder/eg_names' in [e.name for e in entries])

  def test_parametric_parameters(self):
    from lynx.kite import pp
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().deriveScalar(output='pi', expr=pp('${2+1.14}'))
    project = lk.get_project(lk.get_state_id(state))
    scalars = {s.title: lk.get_scalar(s.id) for s in project.scalars}
    self.assertEqual(scalars['pi'].string, '3.14')

  def parametric_ws(self):
    from lynx.kite import pp, text
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().sql(
        pp('select name from `vertices` where age = $ap')).output(name='table')
    ws = lynx.kite.Workspace('ws params', [state], ws_parameters=[text('ap', '18.2')])
    return ws

  def test_parametric_parameters_with_defaults(self):
    lk = lynx.kite.LynxKite()
    ws = self.parametric_ws()
    state_id = lk.get_state_id(ws())
    table = lk.get_table(state_id)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Eve'])

  def test_parametric_parameters_with_workspace_parameters(self):
    lk = lynx.kite.LynxKite()
    ws = self.parametric_ws()
    state_id = lk.get_state_id(ws(ap=20.3))
    table = lk.get_table(state_id)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Adam'])

  def test_wrong_chain_with_multiple_inputs(self):
    lk = lynx.kite.LynxKite()
    with self.assertRaises(Exception) as context:
      state = lk.createExampleGraph().sql2(sql='select * from vertices')
    self.assertTrue('sql2 has more than one input' in str(context.exception))

  def test_trigger_box_with_save_snapshot(self):
    lk = lynx.kite.LynxKite()
    state = (lk.createExampleGraph()
             .sql('select name from vertices')
             .saveToSnapshot(path='this_is_my_snapshot'))
    lk.remove_name('trigger-folder', force=True)
    lk.remove_name('this_is_my_snapshot', force=True)
    ws = lynx.kite.Workspace('trigger-test', [state])
    lk.run_workspace(ws, 'trigger-folder/')
    # The boxId of the "Save to snapshot box" is box_0
    lk.trigger_box('trigger-folder/trigger-test', 'box_0')
    entries = lk.list_dir('')
    self.assertTrue('this_is_my_snapshot' in [e.name for e in entries])

  def test_trigger_box_with_multiple_snapshot_boxes(self):
    lk = lynx.kite.LynxKite()
    eg = lk.createExampleGraph()
    o1 = eg.sql('select name from vertices').saveToSnapshot(path='names_snapshot')
    o2 = eg.sql('select age from vertices').saveToSnapshot(path='ages_snapshot')
    lk.remove_name('names_snapshot', force=True)
    lk.remove_name('ages_snapshot', force=True)
    lk.remove_name('trigger-folder', force=True)
    ws = lynx.kite.Workspace('multi-trigger-test', [o1, o2])
    lk.run_workspace(ws, 'trigger-folder/')
    for box_id in [box['id']
                   for box in ws.to_json('trigger-folder/')
                   if box['operationId'] == 'Save to snapshot']:
      lk.trigger_box('trigger-folder/multi-trigger-test', box_id)
    entries = lk.list_dir('')
    self.assertTrue('names_snapshot' in [e.name for e in entries])
    self.assertTrue('ages_snapshot' in [e.name for e in entries])
