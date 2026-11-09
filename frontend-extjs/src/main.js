import "./app";
Ext.application({
  name: "InfraPilot",
  launch() {
    Ext.Viewport.setLayout("fit");
    Ext.Viewport.add({
      xtype: "container",
      controller: "infrapilot",
      layout: "fit",
    });
  },
});
