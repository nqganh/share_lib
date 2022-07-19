#! /usr/bin/env groovy
package com.rbbn

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class RafAsManifest implements Serializable {

    private def steps

    enum VnfcType { PRIMARY, SECONDARY }

    def NE_TYPES = [
        'sesm',
        'pa',
        'am',
        'db',
        'prov',
        'sm',
        'fpm',
        'mas',
        'bm'
    ]

    def AS_MANIFEST
    def AS_MANIFEST_UNMODIFIED
    def PROVIDER_MANIFEST_DATA
    def PROVIDER_TYPE
    def IMAGE_TYPE

    def networksByName  
    def objectNames
    public String vnfObjectName

    def RafAsManifest (def steps, def manifest, def providerManifestData) {
        this.steps = steps

        this.AS_MANIFEST_UNMODIFIED = null
        this.AS_MANIFEST = manifest
        if(this.AS_MANIFEST['kind'] != 'GenericAsVnf') {
            this.steps.error "Unsupported Manifest kind '${this.AS_MANIFEST['kind']}', only 'GenericAsVnf' is supported"
        }

        this.PROVIDER_MANIFEST_DATA = providerManifestData
        if(this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VmwareProviderAccess') {
            this.PROVIDER_TYPE = "vmware"
            this.IMAGE_TYPE    = "vmdk"
        }
        else if(this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'KvmProviderAccess') {
            this.PROVIDER_TYPE = "kvm"
            this.IMAGE_TYPE    = "qcow2"
        }
        else if(this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VcdProviderAccess') {
            this.PROVIDER_TYPE = "vcd"
            this.IMAGE_TYPE    = "ova"
        }
        else {
            this.steps.error "The first infrastructure manifests in `${PROVIDER_MANIFEST_DATA}` is not 'VmwareProviderAccess', 'KvmProviderAccess' nor 'VcdProviderAccess'"
        }
        this.vnfObjectName = this.AS_MANIFEST['metadata']['name']
    }

    @NonCPS
    def UpdateManifest(def RG_NAME, def SERVER_NETWORKS, def IMAGE_FILENAME, def AS_LICENSE, def MAS_LICENSE, String VNFR_FILENAME = "") {
        def listOfUsedIps = []

        def vnfcs            = this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs
        def serviceAddresses = this.AS_MANIFEST.spec.vnfs[0].appConfig.serviceAddresses
        def networkElements  = this.AS_MANIFEST.spec.vnfs[0].appConfig.networkElements

        // Create a Map of VNFC by name
        def vnfcsByName = vnfcs.inject([:]){ result, vnfc ->
            result[vnfc.name] = vnfc
            result
        }
        
        // List unique networks used in Manifest
        def networksInVnf = vnfcs.collect{
            it.networkInfo.networkInterfaces.collect{it.subnetName}
        }
        networksInVnf = networksInVnf.flatten().unique()
        
        // List unique networks on Provider, skip 1 because the first document is the provider manifest
        def networkManifests = this.PROVIDER_MANIFEST_DATA.drop(1).inject([:]){ result, manifest ->
            result[manifest.spec.subnets[0].name] = manifest
            result
        }
        networksInVnf.each{
            if(!networkManifests.containsKey(it)) {
                this.steps.echo("networksInVnf: $networksInVnf")
                this.steps.echo("networkManifests: $networkManifests")
                this.steps.error("'$it' network in the VNF Manifest not found on provider ("+networkManifests.keySet()+")")
            }
        }

        // Create a Map of networks by name
        def i = 0
		this.objectNames = [this.PROVIDER_MANIFEST_DATA[0].metadata.name]
        def networksByName = networksInVnf.inject([:]){ result, netName ->
            def netInfo = [:]
            def server_network = SERVER_NETWORKS.find{ it.name == netName }
            netInfo.manifest = networkManifests[netName]
            netInfo.ips = server_network.ip
            netInfo.nextIpIdx = 0
            this.objectNames << netInfo.manifest.metadata.name
            result[netName] = netInfo
            result
        }
        this.networksByName = networksByName

        // List of all the objectgs in the Manifest
		this.objectNames << this.AS_MANIFEST.metadata.name
        
        this.steps.echo("## UpdateManifest(): NIC config ##")
        vnfcs.each { vnfc ->
            vnfc.networkInfo.networkInterfaces.eachWithIndex{nic,index->
                def network = networksByName[nic.subnetName]
                nic.ipv4Address = network.ips[network.nextIpIdx++]
                nic.subnetName = network.manifest.spec.subnets[0].name
                nic.networkName = network.manifest.metadata.name
                this.steps.echo("Assign IP: '$nic.ipv4Address' to vnfc: $vnfc.name on network: $nic.subnetName")
                listOfUsedIps << nic.ipv4Address
            }
        }

        this.steps.echo("## UpdateManifest(): internalOamAddress ##")
        networkElements.each{ ne, info ->
            info.managers.each{ manager ->
                if(manager.containsKey("internalOamAddress")){
                    if(manager.internalOamAddress.size()) {
                        // Determine the subnetName of the network for the "internalOamAddress"
                        def subnetName = vnfcsByName[manager.instances[0].server].networkInfo.networkInterfaces[0].subnetName
                        // verify that only one subnet is used for all nics of all VNFC on which this NE is launched
                        if(!manager.instances.every{ vnfcsByName[it.server].networkInfo.networkInterfaces.every{ nic -> nic.subnetName == subnetName } }) {
                            this.steps.echo("WARNING: Not all the $ne's VNFC's nics are on the '$subnetName' network. Using '$subnetName' anyway")
                        }
                        def network = networksByName[subnetName]

                        manager.internalOamAddress = network.ips[network.nextIpIdx++]
                        this.steps.echo("Assign IP: '${manager.internalOamAddress}' to internalOamAddress of NE: $ne, network: ${network.manifest.spec.subnets[0].name}")
                        listOfUsedIps << manager.internalOamAddress
                    }
                }
            }
        }

        this.steps.echo("## UpdateManifest(): serviceAddress ##")
        serviceAddresses.each{ sa ->
            def network
            networkElements.each{ ne, info ->
                info.managers.each{ manager ->
                    if(manager.containsKey("serviceAddressName")) {
                        if(manager.serviceAddressName == sa.name) {
                            // Found the NE that uses this serviceAddress, use the subnet of the first NIC of the first VNFC
                            def subnetName = vnfcsByName[manager.instances[0].server].networkInfo.networkInterfaces[0].subnetName

                            // verify that only one subnet is used for all nics of all VNFC on which this NE is launched
                            if(!manager.instances.every{ vnfcsByName[it.server].networkInfo.networkInterfaces.every{ nic -> nic.subnetName == subnetName } }) {
                                this.steps.echo("WARNING: Not all the $ne's VNFC's nics are on the '$subnetName' network, using '$subnetName' anyway")
                            }
                            network = networksByName[subnetName]

                            sa.ipv4 = network.ips[network.nextIpIdx++]
                            this.steps.echo("Assign IP: '$sa.ipv4' to serviceAddress name: $sa.name,  network: ${network.manifest.spec.subnets[0].name}")
                            listOfUsedIps << sa.ipv4
                        }
                    }
                }
            }
        }

        this.steps.echo("## UpdateManifest(): dependencies ##")
        def j = 0
        def provider = this.AS_MANIFEST.dependencies.providers[0] 
        provider.name = this.PROVIDER_MANIFEST_DATA[0].metadata.name
        networksByName.each{ name, network ->
            provider.networks[j++].name = network.manifest.metadata.name
        }

        this.steps.echo("## UpdateManifest(): Resource Group ##")
        def rg = this.AS_MANIFEST.spec.vnfs[0].rgs[0]
        rg.providerAccessName = provider.name
        rg.providerType = this.PROVIDER_TYPE
        rg.image = IMAGE_FILENAME
        rg.name = RG_NAME
        
        this.steps.echo("## UpdateManifest(): licenses ##")
        def licenses = this.AS_MANIFEST.spec.vnfs[0].appConfig.licenses
        licenses.asLicenseFile.data = AS_LICENSE
        if( licenses.masLicenseFile ) {
            licenses.masLicenseFile.data = MAS_LICENSE
        }

        this.AS_MANIFEST_UNMODIFIED = new JsonSlurper().parseText(JsonOutput.toJson(this.AS_MANIFEST))
        this.steps.echo("## UpdateManifest(): vnfr ##")
        if ( VNFR_FILENAME != "") {
            def appConfig = this.AS_MANIFEST.spec.vnfs[0].appConfig
            appConfig['vnfrFilename'] = VNFR_FILENAME
        }

        return listOfUsedIps
    }
    @NonCPS
    def UpdateImage(String IMAGE_FILENAME, String[] VNFC_LIST = []) {
        def rg = this.AS_MANIFEST.spec.vnfs[0].rgs[0]
        if(VNFC_LIST.size() == 0) {
            rg.image = IMAGE_FILENAME
        }

        def vnfcs = this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs
        vnfcs.each { vnfc ->
            if( VNFC_LIST.contains(vnfc.name) ) {
                vnfc.image = IMAGE_FILENAME
            }
        }
    }

    @NonCPS
    def PreDeployManifestUpdates(def preDeployManifestUpdates) {
        this.steps.echo("Apply pre-deploy manifest updates (${preDeployManifestUpdates.size()})")
        for (manifestUpdate in preDeployManifestUpdates) {
            this.steps.echo("${manifestUpdate['title']}")
            switch (manifestUpdate['type']) {
                case 'removeNEManager':
                    // Remove NE manager and corresponding VNFCs from manifest 
                    // before creating object.
                    this.RemoveNEManagerAndVnfcs(manifestUpdate)
                    break
                // Add new update types here
            }
        }
    }

    /**
     * Remove a single NE manager from appConfig and the corresponding VNFCs
     * from spec.vnfs[0].rgs[0].vnfcs.
     * Used to prepare manifest for deploy before scaling.
     * 
     * @param NE_TYPE
     * @param SHORT_NAME
     * @return result of operation as bool, true on success, false on failure
     **/
    @NonCPS
    def RemoveNEManagerAndVnfcs(Map updateDef) {
        def neType = updateDef.get('neType')
        def shortName = updateDef.get('shortName')

        this.steps.echo("Remove NE manager and corresponding VNFCs: '${neType}': ${shortName}")

        def manifestCopy = new JsonSlurper().parseText(JsonOutput.toJson(this.AS_MANIFEST))

        if (!NE_TYPES.contains(neType)) {
            this.steps.error("Failed to remove AS Network Element manager '${shortName}' from manifest, NE of type ${neType} is not valid.")
            return false
        }

        def networkElements = manifestCopy.spec.vnfs[0].appConfig.networkElements
        if (!networkElements.containsKey(neType)) {
            this.steps.error("Failed to remove AS Network Element manager '${neType}' from manifest, no NE of type ${neType} is defined in the manifest.")
            return false
        }

        def networkElementToScale = networkElements[neType]
        def managerRemoved = null;
        // find NE manager to be removed for deploy prior to scaling
        networkElementToScale.managers.eachWithIndex{manager, managerIdx ->
            if (manager.containsKey('shortName') && manager.shortName == shortName) {
                managerRemoved = this.AS_MANIFEST.spec.vnfs[0].appConfig.networkElements[neType].managers.remove(managerIdx)
            }
        }
        if (managerRemoved == null) {
            // No updates to original manifest
            this.steps.error("Failed to remove AS NE manager '${shortName}' from manifest, no manager with shortName '${shortName}' was found.")
            return false
        }

        def vnfcIdxsToRemove = []
        
        managerRemoved.instances.eachWithIndex{instance, instanceIdx ->
            this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs.eachWithIndex{vnfc, vnfcIdx ->
                if (vnfc.name == instance.server) {
                    vnfcIdxsToRemove.add(vnfcIdx)
                }
            }
        }

        if (vnfcIdxsToRemove.size() == 0) {
            // No VNFCs removed
            this.steps.error("Failed to remove AS NE manager '${shortName}' from manifest, no VNFCs were removed.")
            return false
        }

        vnfcIdxsToRemove.sort()
        vnfcIdxsToRemove = vnfcIdxsToRemove.reverse()

        for (vnfcIdx in vnfcIdxsToRemove) {
            def vnfcRemoved = this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs.remove(vnfcIdx)
            this.steps.echo("Removing VNFC: ${vnfcRemoved.name}")
        }
        
        this.steps.echo("Remaining VNFC count: ${this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs.size()}")

        return true
    }

    @NonCPS
    String[] GetVnfcs(RafAsManifest.VnfcType vnfcType) {
        def vnfcs = []

        def networkElements = this.AS_MANIFEST.spec.vnfs[0].appConfig.networkElements
        networkElements.each{ ne, info ->

            // Check managerId for PROV and PA and instanceId for other NE
            def checkForManagerId = ["prov", "pa"].contains(ne)

            info.managers.eachWithIndex{ manager, mgrIdx ->
                manager.instances.eachWithIndex{ instance, istIdx ->
                    if( (vnfcType == RafAsManifest.VnfcType.PRIMARY &&
                            (istIdx == 0 && !checkForManagerId || mgrIdx == 0 && checkForManagerId ))
                        ||
                        (vnfcType == RafAsManifest.VnfcType.SECONDARY &&
                            (istIdx > 0 && !checkForManagerId || mgrIdx > 0 && checkForManagerId )))
                    {
                        vnfcs << instance.server
                    }
                }
            }
        }
        return vnfcs.unique()
    }

    @NonCPS
    def GetIpParamFromManifest() {
        def ipParam =  [:]
        def netDesc = ""
        def paramName = ""

        def vnfcs            = this.AS_MANIFEST.spec.vnfs[0].rgs[0].vnfcs
        def serviceAddresses = this.AS_MANIFEST.spec.vnfs[0].appConfig.serviceAddresses
        def networkElements  = this.AS_MANIFEST.spec.vnfs[0].appConfig.networkElements

        // Create a Map of VNFC by name
        def vnfcsByName = vnfcs.inject([:]){ result, vnfc ->
            result[vnfc.name] = vnfc
            result
        }
        
        networkElements.each{ ne, info ->
            info.managers.eachWithIndex{ manager, mgrIdx ->
                def mgrIdxStr = ""
                if(info.managers.size() > 1) {
                    mgrIdxStr = "${mgrIdx+1}"
                }
                if(manager.containsKey("internalOamAddress")){
                    if(manager.internalOamAddress.size()) {
                        paramName = "RAF_${ne.toUpperCase()}${mgrIdxStr}_IP" 
                        netDesc +="${paramName}: $manager.internalOamAddress ($ne internalOamAddress)\n"
                        ipParam[paramName] = manager.internalOamAddress
                    }
                }
                if(manager.containsKey("serviceAddressName")){
                    if(manager.serviceAddressName) {
                        def serviceAddress = serviceAddresses.find{ it.name == manager.serviceAddressName }
                        paramName = "RAF_${ne.toUpperCase()}${mgrIdxStr}_IP"
                        netDesc +="${paramName}: ${serviceAddress.ipv4} ($ne ${mgrIdxStr} serviceAddressName. $serviceAddress.name)\n"
                        ipParam[paramName] = serviceAddress.ipv4
                    }
                }
                manager.instances.eachWithIndex{ instance, istIdx ->
                    def istIdxStr = ""
                    if(info.managers.any{ it.instances.size() > 1 }) {
                        istIdxStr = "${istIdx}_"
                    }
                    vnfcsByName[instance.server].networkInfo.networkInterfaces.each{ nic ->
                        def nicIdStr = ""
                        if(vnfcsByName[instance.server].networkInfo.networkInterfaces.size() > 1) {
                            nicIdStr  = "${nic.subnetName}_"
                        }
                        paramName = "RAF_${ne.toUpperCase()}${mgrIdxStr}_${istIdxStr}${nicIdStr}IP"
                        netDesc +="${paramName}: $nic.ipv4Address ($nic.subnetName on $instance.server)\n"
                        ipParam[paramName] = nic.ipv4Address
                    }
                }
            }
        }
        this.steps.echo(netDesc)
        return ipParam
    }

    public def getVnfcRg(String vnfcName) {
        for (rg in this.AS_MANIFEST_UNMODIFIED.spec.vnfs[0].rgs) {
            for (vnfc in rg.vnfcs) {
                if (vnfc.name == vnfcName) {
                    return rg
                }
            }
        }
        return null
    }

    public def getVmName(String rgName, String vnfcName) {
        return "${rgName}-${vnfcName}"
    }

    def GetProviderIp() {
        def serverIp = ""
        if( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VmwareProviderAccess' ) {
            serverIp = this.PROVIDER_MANIFEST_DATA[0]['spec']['secret']['VSPHERE_SERVER']
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'KvmProviderAccess' ) {
            serverIp = this.PROVIDER_MANIFEST_DATA[0]['spec']['hostname']
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VcdProviderAccess') {
            serverIp = this.PROVIDER_MANIFEST_DATA[0]['spec']['url'].split('/')[2]
        }
        return serverIp
    }

    def GetProviderUsername() {
        def username = ""
        if( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VmwareProviderAccess' ) {
            username = this.PROVIDER_MANIFEST_DATA[0]['spec']['secret']['VSPHERE_USER']
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'KvmProviderAccess' ) {
            username = this.PROVIDER_MANIFEST_DATA[0].spec.secret.username
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VcdProviderAccess' ) {
            username = this.PROVIDER_MANIFEST_DATA[0].spec.secret.user
        }
        return username
    }

    def GetProviderPassword() {
        def password = ""
        if( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VmwareProviderAccess' ) {
            password = this.PROVIDER_MANIFEST_DATA[0]['spec']['secret']['VSPHERE_PASSWORD']
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'KvmProviderAccess' ) {
            password = this.PROVIDER_MANIFEST_DATA[0].spec.secret.password
        }
        else if ( this.PROVIDER_MANIFEST_DATA[0]['kind'] == 'VcdProviderAccess' ) {
            password = this.PROVIDER_MANIFEST_DATA[0].spec.secret.password
        }
        return password
    }
}
