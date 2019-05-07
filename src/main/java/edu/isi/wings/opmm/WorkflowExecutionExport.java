package edu.isi.wings.opmm;

import java.io.File;

import org.apache.jena.ontology.Individual;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Resource;

/**
 * Class designed to export WINGS workflow execution traces in RDF according to the OPMW-PROV model.
 * See: http://www.opmw.org/ontology/
 * 
 * This class also has methods for retrieving data in PROV.
 * See https://www.w3.org/TR/prov-o/ and http://purl.org/net/p-plan#
 * 
 * @author Daniel Garijo, with the help of Tirth Rajen Mehta
 */
public class WorkflowExecutionExport {
    private final OntModel wingsExecutionModel;
    private OntModel opmwModel;
    private final Catalog componentCatalog;//needed to publish expanded templates and the extensions of opmw.
    private final String PREFIX_EXPORT_RESOURCE;
    private final String endpointURI;//URI of the endpoint where everything is published.
    private final String exportName;//needed to pass it on to template exports
    private String transformedExecutionURI;
    private WorkflowTemplateExport concreteTemplateExport;
    private boolean isExecPublished;
    private String uploadURL;
    private String uploadUsername;
    private String uploadPassword;
    //private OntModel provModel;//TO IMPLEMENT AT THE END. Can it be done with constructs?

    /**
     * Default constructor for exporting executions
     * @param executionFile
     * @param catalog
     * @param exportName
     * @param endpointURI
     * @param uploadURL
     * @param uploadUsername
     * @param uploadPassword
     */
    public WorkflowExecutionExport(String executionFile, Catalog catalog, String exportName, String endpointURI,
                                   String uploadURL, String uploadUsername, String uploadPassword) {
        this.wingsExecutionModel = ModelUtils.loadModel(executionFile);
        this.opmwModel = ModelUtils.initializeModel(opmwModel);
        this.componentCatalog = catalog;

        PREFIX_EXPORT_RESOURCE = Constants.PREFIX_EXPORT_GENERIC+exportName+"/"+"resource/";
        this.endpointURI = endpointURI;
        this.exportName = exportName;
        this.uploadURL = uploadURL;
        this.uploadUsername = uploadUsername;
        this.uploadPassword = uploadPassword;
        isExecPublished = false;
    }

    /**
     * Function that will return the transformed execution URI.
     * @return transformed execution URI. Null if error
     */
    public String getTransformedExecutionURI() {
        if(transformedExecutionURI == null){
            transform();
        }
        return transformedExecutionURI;
    }

    

    /**
     * Function that will check if an execution exists and then transforms it as RDF under the OPMW model.
     * Assumption: there is a single execution per file.
     */
    public void transform() {
        try{
            //select THE execution instance
            Individual wingsExecution = (Individual) wingsExecutionModel.getOntClass(Constants.WINGS_EXECUTION).listInstances().next();
            //load the plan of the execution. It has critical information such as the
            String plan = wingsExecution.getPropertyValue(wingsExecutionModel.getProperty(Constants.WINGS_PROP_HAS_PLAN)).asResource().getURI();
            wingsExecutionModel.add(ModelUtils.loadModel(plan));
            //ask if an execution with same run id exists. If so, return URI. The local name of the execution is its run id.
            String queryExec = QueriesWorkflowExecutionExport.getOPMWExecutionsWithRunID(wingsExecution.getLocalName());
            QuerySolution solution = ModelUtils.queryOnlineRepository(queryExec, endpointURI);
            if(solution != null){
                System.out.println("Execution exists!");
                this.transformedExecutionURI = (solution.getResource("?exec").getURI());
                isExecPublished = true;
            }else{
                System.out.println("Execution does not exist! Publishing new execution");
                this.transformedExecutionURI =  convertExecutionToOPMW(wingsExecution);
            }
        }catch(Exception e){
          e.printStackTrace();
          System.err.println("Error: "+e.getMessage()+"\n The execution was not exported");
        }
    }

    /**
     * General method to convert a WINGS execution to OPMW
     * @param wingsExecution instance pointer to the execution
     * @return  The URI of the template being generated.
     */
    private String convertExecutionToOPMW(Individual wingsExecution){
        //Create the account and its metadata.
        final String executionTemplateNS = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_WORKFLOW_EXECUTION_ACCOUNT+"/";
        String runID = wingsExecution.getLocalName();
        String we = executionTemplateNS + runID;
        Individual weInstance = opmwModel.createClass(Constants.OPMW_WORKFLOW_EXECUTION_ACCOUNT).createIndividual(we);
        //add label, run id (local name) 
        weInstance.addLabel(runID, null);
        weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_RUN_ID),runID);
        //original log file
        weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_ORIGINAL_EXECUTION_FILE),wingsExecution.getURI());
        //hasTime, endTime and execution status.
        String queryExecutionMetadata = QueriesWorkflowExecutionExport.getWINGSExecutionMetadata(wingsExecution.getURI());
        ResultSet rs = ModelUtils.queryLocalRepository(queryExecutionMetadata, wingsExecutionModel);
        //there is only 1 execution per file
        if(rs.hasNext()){
            QuerySolution qs = rs.next();
            Literal start = qs.getLiteral("?start");
            Literal end = qs.getLiteral("?end");
            Literal status = qs.getLiteral("?status");
            weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_OVERALL_START_TIME),start);
            weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_OVERALL_END_TIME),end);
            weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_STATUS),status);
        }
        //link user. Info not available so it is extracted from URL.
        //TODO: Ask Varun if user can be accessed without deriving it from path
        String user = wingsExecution.getURI().split("/users/")[1];
        user = user.substring(0, user.indexOf("/"));
        String userURI = PREFIX_EXPORT_RESOURCE + Constants.CONCEPT_AGENT+"/"+user;
        Individual userInstance = opmwModel.createClass(Constants.OPM_AGENT).createIndividual(userURI);
        userInstance.addLabel(user, null);
        weInstance.addProperty(opmwModel.createProperty(Constants.PROP_HAS_CONTRIBUTOR), userInstance);

        //metadata of the execution system (WINGS)
        //TODO: Ask Varun to include extra metadata on whether the exec was on Pegasus/OODT, etc.
        String wfSystemURI = PREFIX_EXPORT_RESOURCE + Constants.CONCEPT_AGENT+"/"+"WINGS";
        Individual wingsInstance = opmwModel.createClass(Constants.OPM_AGENT).createIndividual(wfSystemURI);
        weInstance.addProperty(opmwModel.createProperty(Constants.PROP_HAS_CREATOR), wingsInstance);
        wingsInstance.addProperty(opmwModel.createProperty(Constants.PROV_ACTED_ON_BEHALF_OF), userInstance);
        wingsInstance.addLabel("WINGS", null);

        //get expanded template loaded in local model (for parameter linking)
        String queryExpandedTemplate = QueriesWorkflowExecutionExport.getWINGSExpandedTemplate();
        rs = ModelUtils.queryLocalRepository(queryExpandedTemplate , wingsExecutionModel);
        String expandedTemplateURI = null;
        if(rs.hasNext()){
            expandedTemplateURI = rs.next().getResource("?expTemplate").getNameSpace();//the namespace is better for later.
            System.out.println("Execution expanded template "+expandedTemplateURI+" loaded successfully");
            //publish expanded template. The expanded template will publish the template if necessary.
            concreteTemplateExport = new WorkflowTemplateExport(expandedTemplateURI, this.componentCatalog,this.exportName ,this.endpointURI);
            concreteTemplateExport.transform();
            System.out.println(concreteTemplateExport.getTransformedTemplateIndividual());
        }else{
            System.out.println("ERROR: Could not find an expanded template!");
        }
        
        //transform all steps and data dependencies (params are in expanded template)
        String queryExecutionStepMetadata = QueriesWorkflowExecutionExport.getWINGSExecutionStepsAndMetadata();
        rs = ModelUtils.queryLocalRepository(queryExecutionStepMetadata, wingsExecutionModel);
        while(rs.hasNext()){
            QuerySolution qs = rs.next();
            Resource wingsStep = qs.getResource("?step");
            Literal start = qs.getLiteral("?start");
            Literal end = qs.getLiteral("?end");
            Literal status = qs.getLiteral("?status");
            Literal executionScript = qs.getLiteral("?code");
            Literal invLine = qs.getLiteral("?invLine");
            String executionStepURI = PREFIX_EXPORT_RESOURCE+ Constants.CONCEPT_WORKFLOW_EXECUTION_PROCESS+"/"+runID+"_"+wingsStep.getLocalName();
            Individual executionStep = opmwModel.createClass(Constants.OPMW_WORKFLOW_EXECUTION_PROCESS).createIndividual(executionStepURI);
            executionStep.addLabel(wingsStep.getLocalName(), null);
            executionStep.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAD_INVOCATION_COMMAND),invLine);
            executionStep.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAD_START_TIME),start);
            executionStep.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_STATUS),status);
            if(end!=null){
                executionStep.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAD_END_TIME),end);
            }
            String configURI = null;
            String configURIMD5 = null;
            String configLocation = null;//this is where the ZIP file with the contents will be stored
            //Creation of the software config. This may be moved to another class for simplicity and because it
            //can be used in the catalog too.
            //TODO: if there are errors, then create a config URI based on the node and URI (i.e., unique)
            //config URI will be: https://exportResource/SoftwareConfiguration/MD5_Config

            /* Export the component code */
            if(configURI == null){
                  //if the config URI could not be created with MD5, then we give it a unique id (runID+steplocalName)
                configURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_SOFTWARE_CONFIGURATION+"/"+runID+"_"+wingsStep.getLocalName()+"_config";
                configLocation = executionScript.getString().replace("/run", "");
            }
            File directory = new File(configLocation);
            String configLocationRemote = configLocation;
            //TODO: zip and upload
            try {
                configLocationRemote = StorageHandler.zipStreamUpload(directory,
                        this.uploadURL, this.uploadUsername, this.uploadPassword);
            } catch (Exception e) {
                e.printStackTrace();
            }

            /*Export the mainscript and upload */
            Individual stepConfig = opmwModel.createClass(Constants.OPMW_SOFTWARE_CONFIGURATION).createIndividual(configURI);
            stepConfig.addLabel(stepConfig.getLocalName(), null);
            if(configURIMD5 != null){
                stepConfig.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_MD5), configURIMD5);
            }else{
                if (configLocationRemote != null ){
                    configLocation = configLocationRemote;
                }
                stepConfig.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_LOCATION), configLocation);
                //since we don't have MD5, use blank node.
                String mainScriptLocationLocal = executionScript.getString();
                String mainScriptLocation = mainScriptLocationLocal;
                File mainScriptFile = new File(mainScriptLocationLocal);
                try {
                    mainScriptLocation = StorageHandler.uploadFile(mainScriptFile, uploadURL, uploadUsername, uploadPassword);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }

                Resource mainScript = ModelUtils.getIndividualFromFile(mainScriptLocation, opmwModel,
                        Constants.OPMW_SOFTWARE_SCRIPT, null);
                stepConfig.addProperty(opmwModel.createProperty(Constants.OPMW_PROP_HAS_MAIN_SCRIPT),mainScript);
            }

            executionStep.addProperty(opmwModel.createProperty(Constants.OPMW_PROP_HAD_SOFTWARE_CONFIGURATION), stepConfig);
            executionStep.addProperty(opmwModel.createProperty(Constants.OPM_PROP_WCB), wingsInstance);
            //for each step get its i/o (plan)
            String stepVariables = QueriesWorkflowExecutionExport.getWINGSExecutionStepI_O(wingsStep.getURI());
            ResultSet rsVar = ModelUtils.queryLocalRepository(stepVariables, wingsExecutionModel);
            while(rsVar.hasNext()){
                QuerySolution qsVar = rsVar.next();
                String varType = qsVar.getResource("?varType").getURI();
                Resource variable = qsVar.getResource("?variable");
                Literal binding = qsVar.getLiteral("?binding");
                String executionArtifactURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_WORKFLOW_EXECUTION_ARTIFACT+"/"+runID+"_"+variable.getLocalName();
                Individual executionArtifact = opmwModel.createClass(Constants.OPMW_WORKFLOW_EXECUTION_ARTIFACT).createIndividual(executionArtifactURI);
                executionArtifact.addLabel(variable.getLocalName(),null);

                //upload data input or output
                String dataLocation = null;
                try {
                    File dataFile = new File(binding.toString());
                    dataLocation = StorageHandler.uploadFile(dataFile, uploadURL, uploadUsername, uploadPassword);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if ( dataLocation != null ) {
                    executionArtifact.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_LOCATION), dataLocation);
                } else {
                    executionArtifact.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_LOCATION), binding);
                }

                //add as part of the account
                executionArtifact.addProperty(opmwModel.createProperty(Constants.OPM_PROP_ACCOUNT), weInstance);
                if(varType.equals(Constants.P_PLAN_PROP_HAS_INPUT)){
                    //add step used artifact
                    executionStep.addProperty(opmwModel.createProperty(Constants.OPM_PROP_USED), executionArtifact);
                }else if(varType.equals(Constants.P_PLAN_PROP_HAS_OUTPUT)){
                    executionArtifact.addProperty(opmwModel.createProperty(Constants.OPM_PROP_WGB), executionStep);
                }
                //Link to expanded template variable
                String concreteTemplateVariableURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_DATA_VARIABLE+"/"+
                    concreteTemplateExport.getTransformedTemplateIndividual().getLocalName()+"_"+variable.getLocalName();
                Individual concreteTemplateVariable = concreteTemplateExport.getOpmwModel().getIndividual(concreteTemplateVariableURI);
                executionArtifact.addProperty(opmwModel.createAnnotationProperty(Constants.OPMW_PROP_CORRESPONDS_TO_TEMPLATE_ARTIFACT),
                            concreteTemplateVariable);
            }
            //Parameters are specified in the expanded template. The current step is specified with the expanded template URI, same process id.
            String wingsExpandedTempProcessURI = expandedTemplateURI+wingsStep.getLocalName();
            String queryParams = QueriesWorkflowExecutionExport.getWINGSParametersForStep(wingsExpandedTempProcessURI);
            ResultSet params = ModelUtils.queryLocalRepository(queryParams, concreteTemplateExport.getWingsTemplateModel());
            while(params.hasNext()){
                QuerySolution nextP = params.next();
                Resource param = nextP.getResource("?param");
                Literal paramValue = nextP.getLiteral("?paramValue");
                //add param, add its value and link it to execution        
                String parameterURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_WORKFLOW_EXECUTION_ARTIFACT+"/"+runID+"_"+param.getLocalName();
                Individual parameter = opmwModel.createClass(Constants.OPMW_WORKFLOW_EXECUTION_ARTIFACT).createIndividual(parameterURI);
                parameter.addLabel(param.getLocalName(),null);
                parameter.addProperty(opmwModel.createProperty(Constants.OPMW_DATA_PROP_HAS_VALUE), paramValue);
                parameter.addProperty(opmwModel.createProperty(Constants.OPM_PROP_ACCOUNT), weInstance);
                executionStep.addProperty(opmwModel.createProperty(Constants.OPM_PROP_USED), parameter);
                //link parameter to the concrete template
                String concreteTemplateParameterURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_PARAMETER_VARIABLE+"/"+
                    concreteTemplateExport.getTransformedTemplateIndividual().getLocalName()+"_"+param.getLocalName();
                Individual concreteTemplateParameter = concreteTemplateExport.getOpmwModel().getIndividual(concreteTemplateParameterURI);
                parameter.addProperty(opmwModel.createAnnotationProperty(Constants.OPMW_PROP_CORRESPONDS_TO_TEMPLATE_ARTIFACT),
                            concreteTemplateParameter);
                
            }
            //link step to execution
            executionStep.addProperty(opmwModel.createProperty(Constants.OPM_PROP_ACCOUNT), weInstance);
            //link step to concrete template individual
            String concreteTemplateProcessURI = PREFIX_EXPORT_RESOURCE+Constants.CONCEPT_WORKFLOW_TEMPLATE_PROCESS+"/"+
                    concreteTemplateExport.getTransformedTemplateIndividual().getLocalName()+"_"+wingsStep.getLocalName();
            Individual concreteTemplateProcess = concreteTemplateExport.getOpmwModel().getIndividual(concreteTemplateProcessURI);
            executionStep.addProperty(opmwModel.createAnnotationProperty(Constants.OPMW_PROP_CORRESPONDS_TO_TEMPLATE_PROCESS),
                        concreteTemplateProcess);
        }
        //link execution account to expanded template.
        weInstance.addProperty(opmwModel.createProperty(Constants.OPMW_PROP_CORRESPONDS_TO_TEMPLATE),concreteTemplateExport.getTransformedTemplateIndividual());
        return we;
    }
    
    /**
     * Function that exports the transformed template in OPMW. This function should be called after
     * "transform". If not, it will call transform() automatically.
     * @param outFilePath path where to write the serialized model
     * @param serialization serialization of choice: RDF/XML, TTL, etc.
     */
    public void exportAsOPMW(String outFilePath, String serialization){
        if(transformedExecutionURI == null){
            this.transform();
        }
        if(!isExecPublished){
            //opmwModel.write(System.out, "TTL");
            ModelUtils.exportRDFFile(outFilePath+File.separator+opmwModel.getResource(transformedExecutionURI).getLocalName(),opmwModel,serialization);
        }
    }
    
    /**
     * Function that exports the transformed template in P-Plan format. This function should be called after
     * "transform". If not, it will call transform() automatically.
     * @param outFilePath path where to write the serialized model
     * @param serialization serialization of choice: RDF/XML, TTL, etc.
     */
    public void exportAsPROV(String outFilePath, String serialization){
        //TO DO
        System.out.println("Not done yet!");
    }
    
    /**
     * Function that exports the transformed template in OPMW and P-Plan (in different files)
     * @param outFileDirectory path where to write the serialized model
     * @param serialization serialization of choice: RDF/XML, TTL, etc.
     */
    public void exportAll(String outFileDirectory, String serialization){
        //TO DO
        System.out.println("Not done yet!");
        //this.export_as_OPMW(outFilePath, serialization);
        //this.export_as_PPlan(outFilePath, serialization);
    }

    //TO DO: When exporting, do the execution inputs and output collection as I discussed with Milan.?

    public WorkflowTemplateExport getConcreteTemplateExport() {
      return concreteTemplateExport;
    }

    public void setConcreteTemplateExport(WorkflowTemplateExport concreteTemplateExport) {
      this.concreteTemplateExport = concreteTemplateExport;
    }

}